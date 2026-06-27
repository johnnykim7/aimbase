package com.platform.workflow.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.attachment.AttachmentService;
import com.platform.config.WorkspaceProperties;
import com.platform.domain.ChatAttachmentEntity;
import com.platform.domain.LargeInputJobEntity;
import com.platform.largeinput.*;
import com.platform.llm.ConnectionAdapterFactory;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.model.*;
import com.platform.llm.router.ModelRouter;
import com.platform.rag.MCPRagClient;
import com.platform.repository.LargeInputJobRepository;
import com.platform.tenant.TenantContext;
import com.platform.workflow.StepContext;
import com.platform.workflow.analysis.*;
import com.platform.workflow.model.WorkflowStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * CR-120: LARGE_INPUT 스텝 실행기 — 대용량 입력 전수 분석 엔진.
 *
 * <p>흐름: Policy 결정 → 소스 로딩 → 결정론적 분해 → 청크별 map(병렬, 멀티모달) → 계층 Reduce → 누락검증.
 *
 * <p>"동적 FOREACH 위임" 대신 <b>자체 map 루프</b>를 쓰는 이유: 이미지형 청크는 base64 페이지 이미지를
 * 멀티모달 USER 블록({@link ContentBlock.Image})으로 LLM 에 직접 주입해야 하는데, LLM_CALL body 는
 * 텍스트 프롬프트만 받는다. 그래서 엔진이 어댑터를 직접 호출한다. 병렬·상한·재시도는
 * ForeachStepExecutor.runParallel 의 Semaphore+VirtualThread+TenantContext 재주입 패턴을 그대로 차용.
 */
@Component
public class LargeInputStepExecutor implements StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(LargeInputStepExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AttachmentService attachmentService;
    private final List<SourceLoader> sourceLoaders;
    private final LargeInputDecomposerService decomposer;
    private final HierarchicalReduceService reduceService;
    private final CoverageVerifier coverageVerifier;
    private final AnalysisActionRegistry analysisRegistry;
    private final LargeInputJobRepository jobRepository;
    private final ConnectionAdapterFactory connectionAdapterFactory;
    private final ModelRouter modelRouter;
    private final MCPRagClient ragClient;
    private final WorkspaceProperties workspaceProperties;

    @Value("${largeinput.policy:auto}")
    private String systemPolicy;

    @Value("${largeinput.map-max-tokens:8192}")
    private int mapMaxTokens;

    @Value("${largeinput.reduce-max-tokens:8192}")
    private int reduceMaxTokens;

    @Value("${largeinput.image-dpi:100}")
    private int imageDpi;

    public LargeInputStepExecutor(AttachmentService attachmentService,
                                  List<SourceLoader> sourceLoaders,
                                  LargeInputDecomposerService decomposer,
                                  HierarchicalReduceService reduceService,
                                  CoverageVerifier coverageVerifier,
                                  AnalysisActionRegistry analysisRegistry,
                                  LargeInputJobRepository jobRepository,
                                  ConnectionAdapterFactory connectionAdapterFactory,
                                  ModelRouter modelRouter,
                                  MCPRagClient ragClient,
                                  WorkspaceProperties workspaceProperties) {
        this.attachmentService = attachmentService;
        this.sourceLoaders = sourceLoaders;
        this.decomposer = decomposer;
        this.reduceService = reduceService;
        this.coverageVerifier = coverageVerifier;
        this.analysisRegistry = analysisRegistry;
        this.jobRepository = jobRepository;
        this.connectionAdapterFactory = connectionAdapterFactory;
        this.modelRouter = modelRouter;
        this.ragClient = ragClient;
        this.workspaceProperties = workspaceProperties;
    }

    @Override
    public WorkflowStep.StepType supports() {
        return WorkflowStep.StepType.LARGE_INPUT;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(WorkflowStep step, StepContext context) {
        Map<String, Object> raw = step.config() != null ? step.config() : Map.of();
        Map<String, Object> config = context.resolveMap(raw);

        // ── 1) config 해석 + 동작 + Policy ──────────────────────────────────
        String actionId = str(config.get("analysis_action"), null);
        if (actionId == null) {
            throw new IllegalArgumentException("LARGE_INPUT step '" + step.id() + "': analysis_action is required");
        }
        DocumentAnalysis action = analysisRegistry.get(actionId);

        AnalysisParams params = new AnalysisParams(
                asStringList(raw.get("focus_areas"), context),
                (Map<String, Object>) raw.get("output_schema"),
                str(config.get("analysis_goal"), ""),
                str(config.get("reference_input"), null),
                str(config.get("custom_instruction"), null),
                (Map<String, Object>) raw.get("extra"));

        if (action.needsReference() && (params.referenceInput() == null || params.referenceInput().isBlank())) {
            throw new IllegalArgumentException("LARGE_INPUT step '" + step.id() + "': action '" + actionId
                    + "' requires reference_input (needsReference=true)");
        }

        int maxParallel = asInt(config.get("max_parallel"), 5);
        int itemRetryMax = asInt(config.get("item_retry_max"), 1);
        boolean requireFullCoverage = asBool(config.get("require_full_coverage"), true);
        String model = str(config.get("model"), "auto");
        String connectionId = str(config.get("connection_id"), null);
        Map<String, Object> responseSchema = (Map<String, Object>) raw.get("output_schema");

        // 청크 처리 어댑터를 먼저 해석한다(connection_id 가 처리 백엔드를 결정 — CLI vs API).
        // 별도 execution_backend 플래그는 없앴다(connection_id 로 단일화).
        LLMAdapter adapter = resolveAdapter(connectionId, model);
        String resolvedModel = resolveModel(connectionId, model);

        LargeInputPolicy policy = LargeInputPolicy.resolve(
                str(config.get("large_input_mode"), null), null, systemPolicy);
        // CLI(자율주행) 어댑터는 컨텍스트 크기를 우리가 사전측정 못 함 → auto 를 force 로 정규화
        // (LLM API 어댑터는 우리가 청크를 직접 주입해 크기 통제 → auto 그대로).
        if (policy == LargeInputPolicy.AUTO && adapter instanceof com.platform.llm.adapter.ClaudeCliAdapter) {
            policy = LargeInputPolicy.FORCE;
            log.info("CR-120 step '{}': CLI adapter → AUTO normalized to FORCE", step.id());
        }

        // ── 2) 소스 로딩 ───────────────────────────────────────────────────
        LargeInputSource source = loadSource(step, config, context);

        // ── 3) job 저장 (DECOMPOSING) ─────────────────────────────────────
        LargeInputJobEntity job = newJob(context, step, actionId, source);
        job.setStatus(LargeInputJobEntity.Status.DECOMPOSING);
        job = jobRepository.save(job);

        if (policy == LargeInputPolicy.FORBID && isLikelyLarge(source)) {
            job.setStatus(LargeInputJobEntity.Status.FAILED);
            jobRepository.save(job);
            throw new IllegalStateException("LARGE_INPUT step '" + step.id()
                    + "': large_input_mode=forbid but input is large — refusing (would risk 32MB limit)");
        }

        // ── 4) 분해 ────────────────────────────────────────────────────────
        List<LargeInputChunk> chunks = decomposer.decompose(source, policy);
        job.setChunks(chunks.stream().map(LargeInputChunk::toMeta).toList());
        job.setTotalChunks(chunks.size());
        job.setStatus(LargeInputJobEntity.Status.PROCESSING);
        job = jobRepository.save(job);
        log.info("CR-120 step '{}': {} chunks (action={}, policy={}, adapter={})",
                step.id(), chunks.size(), actionId, policy, adapter.getClass().getSimpleName());

        // ── 5) map (병렬, 멀티모달) ────────────────────────────────────────
        AnalysisInstruction mapInstr = action.buildMapInstruction(params);
        byte[] pdfBytes = source.bytes();
        // CLI 어댑터 좀비 워커 정리(CR-109/114)를 위한 결정적 sessionId base.
        // 청크별로 "-c{index}" 를 붙여 각 청크 호출이 자기 워커를 cleanupSession 으로 닫게 한다.
        String sessionBase = context.workflowRunId() + "-li-" + step.id();

        List<ChunkRun> runs = mapChunks(chunks, action, mapInstr, adapter, resolvedModel,
                responseSchema, pdfBytes, maxParallel, itemRetryMax, sessionBase);

        // chunk_results + 카운터 적재
        List<Map<String, Object>> chunkResults = new ArrayList<>(runs.size());
        int completed = 0;
        for (ChunkRun r : runs) {
            Map<String, Object> cr = new LinkedHashMap<>();
            cr.put("chunk_index", r.chunk.chunkIndex());
            cr.put("page_range", r.chunk.pageRange());
            cr.put("ok", r.ok);
            if (r.ok) {
                completed++;
                cr.put("output_preview", preview(r.text));
                if (r.structured != null) cr.put("structured_data", r.structured);
            } else {
                cr.put("reason", r.failureReason);
            }
            chunkResults.add(cr);
        }
        job.setChunkResults(chunkResults);
        job.setCompleted(completed);
        job.setFailed(runs.size() - completed);
        job = jobRepository.save(job);

        // ── 6) 계층 Reduce (성공 청크만) ───────────────────────────────────
        job.setStatus(LargeInputJobEntity.Status.REDUCING);
        job = jobRepository.save(job);
        // 청크 결과를 reduce 입력으로 — structured_data 가 있으면 JSON 문자열로 포함해 보존한다
        // (텍스트만 넘기면 청크 구조화 결과가 reduce 에서 유실됨).
        List<String> successFragments = runs.stream().filter(r -> r.ok).map(this::fragmentOf).toList();
        AnalysisInstruction reduceInstr = action.buildReduceInstruction(params);
        Map<String, Object> reduceTree = new LinkedHashMap<>();
        final LLMAdapter fAdapter = adapter;
        final String fModel = resolvedModel;
        final java.util.concurrent.atomic.AtomicInteger reduceSeq = new java.util.concurrent.atomic.AtomicInteger();
        // 계층 텍스트 머지(중간 레벨) → 최종 1개 텍스트로 수렴.
        String reduced = reduceService.reduce(successFragments, reduceInstr,
                (sys, prompt) -> callLlmText(fAdapter, fModel, sys, prompt, null, reduceMaxTokens,
                        sessionBase + "-r" + reduceSeq.getAndIncrement()),
                reduceTree);
        // output_schema 가 있으면 최종 머지 텍스트를 한 번 더 구조화 호출해 진짜 Map 을 만든다
        // (청크는 structured 인데 reduce 산출물이 텍스트면 verify 가 JSON 을 못 받는 갭 해소).
        Map<String, Object> reducedStructured = null;
        if (responseSchema != null && reduced != null && !reduced.isBlank()) {
            reducedStructured = reduceToStructured(adapter, resolvedModel, reduceInstr.system(),
                    reduced, responseSchema, sessionBase + "-rs");
        }
        job.setReduceTree(reduceTree);
        job = jobRepository.save(job);

        // ── 7) 검증 ────────────────────────────────────────────────────────
        job.setStatus(LargeInputJobEntity.Status.VERIFYING);
        job = jobRepository.save(job);
        List<CoverageVerifier.ChunkOutcome> outcomes = runs.stream()
                .map(r -> new CoverageVerifier.ChunkOutcome(r.chunk, r.ok, r.failureReason)).toList();
        Map<String, Object> coverageReport = coverageVerifier.verify(outcomes);
        job.setCoverageReport(coverageReport);

        if (requireFullCoverage && !coverageVerifier.isFullCoverage(coverageReport)) {
            job.setStatus(LargeInputJobEntity.Status.FAILED);
            jobRepository.save(job);
            throw new IllegalStateException("LARGE_INPUT step '" + step.id()
                    + "': full coverage not met (require_full_coverage=true). report=" + coverageReport);
        }

        // ── 8) 완료 ────────────────────────────────────────────────────────
        job.setStatus(LargeInputJobEntity.Status.COMPLETED);
        jobRepository.save(job);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("output", reduced != null ? reduced : "");
        // output_schema 가 있고 구조화에 성공했으면 진짜 Map, 아니면 텍스트(하위호환).
        result.put("structured_data", reducedStructured != null ? reducedStructured : reduced);
        result.put("coverage_report", coverageReport);
        result.put("total_chunks", chunks.size());
        result.put("completed", completed);
        result.put("failed", runs.size() - completed);
        result.put("job_id", job.getJobId().toString());
        return result;
    }

    // ─── map: 청크 병렬 처리 ──────────────────────────────────────────────────

    /** 청크 처리 1건 결과. */
    private static final class ChunkRun {
        final LargeInputChunk chunk;
        boolean ok;
        String text;
        Map<String, Object> structured;
        String failureReason;
        ChunkRun(LargeInputChunk chunk) { this.chunk = chunk; }
    }

    private List<ChunkRun> mapChunks(List<LargeInputChunk> chunks, DocumentAnalysis action,
                                     AnalysisInstruction mapInstr, LLMAdapter adapter, String resolvedModel,
                                     Map<String, Object> responseSchema, byte[] pdfBytes,
                                     int maxParallel, int itemRetryMax, String sessionBase) {
        Semaphore gate = new Semaphore(Math.max(1, maxParallel));
        final String tenantId = TenantContext.getTenantId();
        List<CompletableFuture<ChunkRun>> futures = new ArrayList<>(chunks.size());
        for (LargeInputChunk chunk : chunks) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                if (tenantId != null) TenantContext.setTenantId(tenantId);
                gate.acquireUninterruptibly();
                try {
                    return runChunk(chunk, action, mapInstr, adapter, resolvedModel,
                            responseSchema, pdfBytes, itemRetryMax,
                            sessionBase + "-c" + chunk.chunkIndex());
                } finally {
                    gate.release();
                    TenantContext.clear();
                }
            }, Executors.newVirtualThreadPerTaskExecutor()));
        }
        List<ChunkRun> runs = new ArrayList<>(chunks.size());
        for (CompletableFuture<ChunkRun> f : futures) runs.add(f.join());
        return runs;
    }

    /** 단일 청크: 텍스트/이미지에 맞게 메시지 조립 → LLM 호출 → 검증 → (실패 시) 재시도. */
    private ChunkRun runChunk(LargeInputChunk chunk, DocumentAnalysis action, AnalysisInstruction mapInstr,
                              LLMAdapter adapter, String resolvedModel, Map<String, Object> responseSchema,
                              byte[] pdfBytes, int itemRetryMax, String chunkSessionId) {
        ChunkRun run = new ChunkRun(chunk);
        int maxAttempts = 1 + Math.max(0, itemRetryMax);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                List<ContentBlock> userBlocks = buildChunkBlocks(chunk, mapInstr.prompt(), pdfBytes);
                LLMResponse resp = callLlm(adapter, resolvedModel, mapInstr.system(), userBlocks,
                        responseSchema, mapMaxTokens, chunkSessionId);
                Map<String, Object> chunkResult = new LinkedHashMap<>();
                chunkResult.put("output", resp.textContent());
                Map<String, Object> structured = extractStructured(resp);
                if (structured != null) chunkResult.put("structured_data", structured);

                ValidationResult v = action.validateChunkResult(chunkResult);
                if (!v.valid()) {
                    throw new IllegalStateException("chunk validation failed: " + v.message());
                }
                run.ok = true;
                run.text = resp.textContent();
                run.structured = structured;
                return run;
            } catch (Exception e) {
                run.failureReason = e.getMessage();
                if (attempt < maxAttempts) {
                    log.warn("CR-120 chunk[{}] {} attempt {}/{} failed: {} — retrying",
                            chunk.chunkIndex(), chunk.pageRange(), attempt, maxAttempts, e.getMessage());
                } else {
                    log.warn("CR-120 chunk[{}] {} FAILED after {} attempt(s): {}",
                            chunk.chunkIndex(), chunk.pageRange(), maxAttempts, e.getMessage());
                }
            }
        }
        run.ok = false;
        return run;
    }

    /**
     * 청크 → USER 메시지 블록. 텍스트형은 프롬프트 {{chunk}} 에 텍스트 삽입,
     * 이미지형은 프롬프트 텍스트 블록 + 페이지 이미지 블록들을 멀티모달로 조립.
     */
    private List<ContentBlock> buildChunkBlocks(LargeInputChunk chunk, String promptTemplate, byte[] pdfBytes) {
        if (chunk.type() == LargeInputChunk.Type.TEXT) {
            String prompt = promptTemplate.replace("{{chunk}}", chunk.text() != null ? chunk.text() : "");
            return List.of(new ContentBlock.Text(prompt));
        }
        // IMAGE: 프롬프트(자리표시자는 안내문으로 치환) + 페이지 이미지들
        String prompt = promptTemplate.replace("{{chunk}}",
                "[document pages " + chunk.pageRange() + " are attached below as images]");
        List<ContentBlock> blocks = new ArrayList<>();
        blocks.add(new ContentBlock.Text(prompt));
        for (PdfVisionPage page : renderPages(pdfBytes, chunk)) {
            blocks.add(ContentBlock.Image.ofBase64(page.mediaType(), page.base64()));
        }
        if (blocks.size() == 1) {
            throw new IllegalStateException("chunk[" + chunk.chunkIndex() + "] "
                    + chunk.pageRange() + ": no page images rendered");
        }
        return blocks;
    }

    private record PdfVisionPage(String mediaType, String base64) {}

    private List<PdfVisionPage> renderPages(byte[] pdfBytes, LargeInputChunk chunk) {
        String base64 = Base64.getEncoder().encodeToString(pdfBytes);
        int maxPages = chunk.pageEnd() - chunk.pageStart() + 1;
        Map<String, Object> r = ragClient.pdfToImages(base64, chunk.pageRange(), imageDpi, maxPages);
        if (!Boolean.TRUE.equals(r.get("success")) || !(r.get("images") instanceof List<?> images)) {
            return List.of();
        }
        List<PdfVisionPage> out = new ArrayList<>();
        for (Object o : images) {
            if (o instanceof Map<?, ?> img && img.get("data") instanceof String data) {
                String mt = img.get("media_type") instanceof String s ? s : "image/jpeg";
                out.add(new PdfVisionPage(mt, data));
            }
        }
        return out;
    }

    // ─── LLM 호출 (멀티모달 USER 블록 지원) ──────────────────────────────────

    /**
     * 청크/Reduce LLM 호출. {@code sessionId} 는 CLI 어댑터의 좀비 워커 정리(CR-109/114) 키 —
     * 결정적 청크별 id 를 넣어 호출 후 {@link LLMAdapter#cleanupSession}(CLI 만 동작)으로 워커를 닫는다.
     * LLM API 어댑터는 cleanupSession 이 no-op 이라 무해.
     */
    private LLMResponse callLlm(LLMAdapter adapter, String resolvedModel, String system,
                                List<ContentBlock> userBlocks, Map<String, Object> responseSchema,
                                int maxTokens, String sessionId) {
        List<UnifiedMessage> messages = new ArrayList<>();
        if (system != null && !system.isBlank()) {
            messages.add(UnifiedMessage.ofText(UnifiedMessage.Role.SYSTEM, system));
        }
        messages.add(UnifiedMessage.ofUserContent(userBlocks));
        ModelConfig cfg = new ModelConfig(null, maxTokens, null, null, null, null);
        LLMRequest request = new LLMRequest(resolvedModel, messages, null, cfg, false, sessionId, null,
                responseSchema, null);
        try {
            return adapter.chat(request).get();
        } catch (Exception e) {
            throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
        } finally {
            // CLI 어댑터면 이 청크 전용 워커를 결정적으로 닫는다(좀비 누수 차단). API 어댑터는 no-op.
            if (sessionId != null && !sessionId.isBlank()) {
                try {
                    adapter.cleanupSession(sessionId);
                } catch (RuntimeException ce) {
                    log.warn("CR-120: cleanupSession 실패(sessionId={}): {}", sessionId, ce.getMessage());
                }
            }
        }
    }

    /**
     * 청크 결과 → reduce 입력 fragment. structured_data 가 있으면 텍스트와 함께 JSON 으로 직렬화해
     * reduce 가 구조를 잃지 않게 한다. structured 없으면 텍스트 그대로.
     */
    private String fragmentOf(ChunkRun run) {
        if (run.structured != null && !run.structured.isEmpty()) {
            try {
                String json = MAPPER.writeValueAsString(run.structured);
                String text = run.text != null && !run.text.isBlank() ? run.text + "\n" : "";
                return text + json;
            } catch (Exception e) {
                log.warn("CR-120: chunk structured 직렬화 실패(idx={}) — 텍스트로 폴백: {}",
                        run.chunk.chunkIndex(), e.getMessage());
            }
        }
        return run.text != null ? run.text : "";
    }

    /**
     * 최종 머지 텍스트를 output_schema 로 한 번 더 구조화 호출 → 진짜 Map.
     * 청크는 structured 인데 reduce 산출물이 텍스트가 되던 갭(A1)을 메운다. 실패하면 null(텍스트 폴백).
     */
    private Map<String, Object> reduceToStructured(LLMAdapter adapter, String resolvedModel, String system,
                                                   String mergedText, Map<String, Object> responseSchema,
                                                   String sessionId) {
        String prompt = "Convert the following consolidated analysis into the required structured output. "
                + "Preserve every item faithfully — do not drop or invent.\n\n" + mergedText;
        try {
            LLMResponse resp = callLlm(adapter, resolvedModel, system,
                    List.of(new ContentBlock.Text(prompt)), responseSchema, reduceMaxTokens, sessionId);
            return extractStructured(resp);
        } catch (RuntimeException e) {
            log.warn("CR-120: reduce 구조화 호출 실패 — 텍스트 결과로 폴백: {}", e.getMessage());
            return null;
        }
    }

    /** Reduce 콜백용 텍스트 전용 호출. */
    private String callLlmText(LLMAdapter adapter, String resolvedModel, String system, String prompt,
                               Map<String, Object> responseSchema, int maxTokens, String sessionId) {
        LLMResponse resp = callLlm(adapter, resolvedModel, system,
                List.of(new ContentBlock.Text(prompt)), responseSchema, maxTokens, sessionId);
        return resp.textContent();
    }

    // ─── 소스 로딩 ────────────────────────────────────────────────────────────

    private LargeInputSource loadSource(WorkflowStep step, Map<String, Object> config, StepContext context) {
        String sourceFile = str(config.get("source_file"), null);
        String inlineInput = str(config.get("input"), null);

        if (sourceFile != null && !sourceFile.isBlank()) {
            String trimmed = sourceFile.trim();
            // source_file 은 두 형태를 받는다:
            //  ① attachment_id(UUID) → AttachmentService 로 세션 소유 attachment 바이트 로드
            //  ② 작업장 경로 → whitelist 검증 후 BE 가 직접 읽기 (ParseDocumentTool file_path 패턴)
            // 운영 워크플로우(opportunity-analysis)가 download 한 작업장 파일 경로를 그대로 쓰게 하기 위함.
            return isUuid(trimmed)
                    ? loadAttachment(UUID.fromString(trimmed), context)
                    : loadWorkspaceFile(step, trimmed, context);
        }
        if (inlineInput != null) {
            return new LargeInputSource("inline", "text/plain", null, inlineInput, null);
        }
        throw new IllegalArgumentException("LARGE_INPUT step '" + step.id()
                + "': either source_file (attachment_id or workspace path) or input (inline text) is required");
    }

    /** ① attachment_id 경로 — 세션 소유 attachment 바이트 로드. */
    private LargeInputSource loadAttachment(UUID attachmentId, StepContext context) {
        ChatAttachmentEntity att = attachmentService.loadOwned(attachmentId, context.sessionId());
        byte[] bytes = attachmentService.readBytes(att);
        String mime = att.getMediaType();
        return pickLoader(mime).load(attachmentId.toString(), mime, bytes);
    }

    /**
     * ② 작업장 경로 경로 — whitelist 검증 후 BE 가 직접 읽는다(ParseDocumentTool file_path 패턴).
     * mime 메타가 없으므로 확장자로 추론. 운영 download_attachments 가 떨군 작업장 파일을 그대로 분석.
     */
    private LargeInputSource loadWorkspaceFile(WorkflowStep step, String rawPath, StepContext context) {
        // run 격리 작업장({base}/{tenant}/runs/{runId}, StepContext.workspacePath)을 base 로 우선 사용.
        // TOOL_CALL(download_attachments)/AGENT_CALL 이 파일을 떨구는 run 디렉토리와 동일하게 맞춰,
        // 글로벌 base(/data/workspace)에서 못 찾던 버그(CR-120)를 해소. null=하위호환 폴백.
        String wsPath = context == null ? null : context.workspacePath();
        Path base = (wsPath != null && !wsPath.isBlank())
                ? Path.of(wsPath)
                : Path.of(workspaceProperties.getBase());
        Path p = Path.of(rawPath);
        // 상대경로면 작업장 base 기준으로 해석(예: "attachments/x.pdf" → <base>/attachments/x.pdf)
        Path abs = (p.isAbsolute() ? p : base.resolve(p)).toAbsolutePath().normalize();
        if (!workspaceProperties.isInsideWhitelist(abs)) {
            throw new IllegalArgumentException("LARGE_INPUT step '" + step.id()
                    + "': source_file path is outside workspace whitelist: " + abs);
        }
        if (!Files.isReadable(abs)) {
            throw new IllegalArgumentException("LARGE_INPUT step '" + step.id()
                    + "': source_file not found or unreadable in workspace: " + abs);
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(abs);
        } catch (IOException e) {
            throw new IllegalStateException("LARGE_INPUT step '" + step.id()
                    + "': failed to read workspace file " + abs + ": " + e.getMessage(), e);
        }
        String mime = guessMimeFromName(abs.getFileName().toString());
        return pickLoader(mime).load(abs.toString(), mime, bytes);
    }

    private SourceLoader pickLoader(String mime) {
        return sourceLoaders.stream()
                .filter(l -> l.supports(mime)).findFirst()
                .orElseThrow(() -> new IllegalStateException("LARGE_INPUT: no SourceLoader for mime " + mime));
    }

    private static boolean isUuid(String s) {
        try {
            UUID.fromString(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 확장자 → MIME (MVP: PDF만 분해 지원, 그 외는 일반 타입으로 두고 SourceLoader 가 거름). */
    private static String guessMimeFromName(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        return "application/octet-stream";
    }

    private boolean isLikelyLarge(LargeInputSource source) {
        if (source.bytes() != null) return source.bytes().length > 20 * 1024 * 1024; // >20MB raw
        if (source.inlineText() != null) return source.inlineText().length() > 100_000;
        return false;
    }

    private LargeInputJobEntity newJob(StepContext context, WorkflowStep step, String actionId,
                                       LargeInputSource source) {
        LargeInputJobEntity job = new LargeInputJobEntity();
        job.setJobId(UUID.randomUUID());
        job.setRunId(parseUuid(context.workflowRunId()));
        job.setStepId(step.id());
        job.setAnalysisAction(actionId);
        job.setSourceRef(source.sourceId());
        return job;
    }

    // ─── 어댑터/모델 해석 (LlmCallStepExecutor 패턴) ──────────────────────────

    private LLMAdapter resolveAdapter(String connectionId, String model) {
        if (connectionId != null && !connectionId.isBlank()) {
            return connectionAdapterFactory.getAdapter(connectionId);
        }
        String resolvedModel = modelRouter.resolveModelId(model);
        return modelRouter.route(new LLMRequest(resolvedModel, List.of()));
    }

    private String resolveModel(String connectionId, String model) {
        if (connectionId != null && !connectionId.isBlank()) {
            return connectionAdapterFactory.resolveModel(connectionId, model);
        }
        return modelRouter.resolveModelId(model);
    }

    private Map<String, Object> extractStructured(LLMResponse resp) {
        return resp.content().stream()
                .filter(b -> b instanceof ContentBlock.Structured)
                .map(b -> ((ContentBlock.Structured) b).data())
                .findFirst().orElse(null);
    }

    // ─── 헬퍼 ────────────────────────────────────────────────────────────────

    private static UUID parseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (Exception e) {
            // workflowRunId 가 UUID 형식이 아니면 결정적 UUID 로 매핑(추적용)
            return UUID.nameUUIDFromBytes((s != null ? s : "").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private List<String> asStringList(Object v, StepContext context) {
        Object resolved = context.resolveObject(v);
        Object use = resolved != null ? resolved : v;
        if (use instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object o : list) if (o != null) out.add(o.toString());
            return out;
        }
        return List.of();
    }

    private static String preview(String s) {
        if (s == null) return "";
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    private static String str(Object v, String def) {
        return v == null || v.toString().isBlank() ? def : v.toString();
    }

    private static int asInt(Object v, int def) {
        if (v instanceof Number n) return n.intValue();
        if (v == null) return def;
        try { return Integer.parseInt(v.toString().trim()); } catch (NumberFormatException e) { return def; }
    }

    private static boolean asBool(Object v, boolean def) {
        if (v instanceof Boolean b) return b;
        if (v == null) return def;
        return Boolean.parseBoolean(v.toString().trim());
    }
}
