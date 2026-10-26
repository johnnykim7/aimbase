package com.platform.workflow.step;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.llm.ConnectionAdapterFactory;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.adapter.StructuredOutputNormalizer;
import com.platform.llm.model.*;
import com.platform.llm.router.ModelRouter;
import com.platform.schema.SchemaValidator;
import com.platform.skill.StepSkillService;
import com.platform.workflow.StepContext;
import com.platform.workflow.event.WorkflowEventPublisher;
import com.platform.workflow.event.WorkflowRunEventRecorder;
import com.platform.workflow.model.WorkflowStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * LLM_CALL 스텝 실행기.
 *
 * 토큰 초과 자동 처리 전략:
 *   Phase 1: 일반 호출 — step config의 max_tokens 지정 시 그 값으로, 미지정 시 4096
 *   Phase 2: 에스컬레이션 (8192) — 살짝 넘는 경우 (max_tokens 미지정 케이스에서만 발생)
 *   Phase 3: 자동분할 — 크게 넘는 경우
 *     3-a: 분할 계획 호출 → 파트 목록
 *     3-b: 파트별 실행 (각 4096)
 *     3-c: 취합 호출 → 최종 결과
 *
 * Phase 1 시작값 정책: step config의 max_tokens 는 출력 크기에 대한 소비앱의 선언이다.
 * 지정되면 그 값으로 1차 호출(큰 출력 WF가 4096→8192 에스컬레이션 왕복을 헛되이 겪지 않음).
 * 미지정이면 출력 크기를 모르는 것이므로 4096부터 시작해 필요할 때만 올린다(비용 절감).
 *
 * 소비앱은 분할 여부를 모름 — response_schema에 맞는 완성된 JSON만 받음.
 */
@Component
public class LlmCallStepExecutor implements StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(LlmCallStepExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** max_tokens 미지정 시 Phase 1 시작값 (출력 크기를 모를 때의 비용 절감 기본). */
    private static final int INITIAL_MAX_TOKENS = 4096;
    private static final int ESCALATION_MAX_TOKENS = 8192;
    private static final int SPLIT_PART_MAX_TOKENS = 4096;
    private static final int SPLIT_MAX_PARTS = 10;
    /** CR-143: BIZ-034 구조화 출력 재요청 횟수 (채팅 경로 OrchestratorEngine 과 같은 값). */
    private static final int STRUCTURED_OUTPUT_MAX_RETRIES = 2;

    private final ModelRouter modelRouter;
    private final ConnectionAdapterFactory connectionAdapterFactory;
    private final com.platform.service.PromptTemplateService promptTemplateService;
    // CR-085 P3: 노드 내부 토큰 스트리밍 발행 (opt-in). null 허용 — 기존 테스트/비스트리밍 경로 무영향.
    private final WorkflowEventPublisher eventPublisher;
    /** CR-090: workflow_run_events 비동기 기록. null 허용(테스트 편의). */
    private final WorkflowRunEventRecorder eventRecorder;
    private final SchemaValidator schemaValidator;
    /** CR-147: 단계 지정 스킬. null 이면 config.skills 를 무시한다(테스트 편의 3-arg 생성자). */
    private final StepSkillService stepSkills;
    /** CR-159: config.attachments 해석. null 이면 attachments 를 준 단계는 실패한다(테스트 편의 3-arg 생성자). */
    private final StepAttachmentResolver attachmentResolver;

    @org.springframework.beans.factory.annotation.Autowired
    public LlmCallStepExecutor(ModelRouter modelRouter, ConnectionAdapterFactory connectionAdapterFactory,
                                com.platform.service.PromptTemplateService promptTemplateService,
                                org.springframework.beans.factory.ObjectProvider<WorkflowEventPublisher> eventPublisherProvider,
                                org.springframework.beans.factory.ObjectProvider<WorkflowRunEventRecorder> eventRecorderProvider,
                                SchemaValidator schemaValidator,
                                org.springframework.beans.factory.ObjectProvider<StepSkillService> stepSkillsProvider,
                                org.springframework.beans.factory.ObjectProvider<StepAttachmentResolver> attachmentResolverProvider) {
        this.modelRouter = modelRouter;
        this.promptTemplateService = promptTemplateService;
        this.connectionAdapterFactory = connectionAdapterFactory;
        // ObjectProvider 로 순환참조 회피 (WorkflowEventPublisher 는 워크플로우 패키지 컴포넌트)
        this.eventPublisher = eventPublisherProvider != null ? eventPublisherProvider.getIfAvailable() : null;
        this.eventRecorder = eventRecorderProvider != null ? eventRecorderProvider.getIfAvailable() : null;
        this.schemaValidator = schemaValidator;
        this.stepSkills = stepSkillsProvider != null ? stepSkillsProvider.getIfAvailable() : null;
        this.attachmentResolver = attachmentResolverProvider != null ? attachmentResolverProvider.getIfAvailable() : null;
    }

    /** 하위 호환: eventPublisher/eventRecorder 없는 3-arg 생성자 (기존 테스트/비스트리밍 사용처). */
    public LlmCallStepExecutor(ModelRouter modelRouter, ConnectionAdapterFactory connectionAdapterFactory,
                                com.platform.service.PromptTemplateService promptTemplateService) {
        this.modelRouter = modelRouter;
        this.promptTemplateService = promptTemplateService;
        this.connectionAdapterFactory = connectionAdapterFactory;
        this.eventPublisher = null;
        this.eventRecorder = null;
        this.schemaValidator = new SchemaValidator(MAPPER);
        this.stepSkills = null;
        this.attachmentResolver = null;
    }

    @Override
    public WorkflowStep.StepType supports() {
        return WorkflowStep.StepType.LLM_CALL;
    }

    /** CR-147: 단계 지정 스킬의 안내서와 files 내용을 system 뒤에 붙인다(T3-17 3.6). 상한을 넘으면 단계 실패. */
    private String withSkills(String system, WorkflowStep step, StepContext context) {
        if (stepSkills == null) return system;
        String skillText = stepSkills.inlineText(stepSkills.pinsFor(step, context).skills());
        if (skillText.isEmpty()) return system;
        return system == null || system.isBlank() ? skillText : system + "\n\n" + skillText;
    }

    @Override
    public Map<String, Object> execute(WorkflowStep step, StepContext context) {
        Map<String, Object> config = step.config();

        String model = config.containsKey("model") ? (String) config.get("model") : "auto";
        String connectionId = (String) config.get("connection_id");

        // CR-029: workflow step runtime 필드 (향후 RuntimeAdapter 분기 지점)
        String runtime = (String) config.getOrDefault("runtime", "llm_api");
        String runtimeMode = (String) config.getOrDefault("runtime_mode", "stateless");
        log.debug("Step '{}': runtime={}, mode={}", step.id(), runtime, runtimeMode);
        String promptTemplate = (String) config.getOrDefault("prompt", "");
        String systemTemplate = (String) config.get("system");
        Integer configCeiling = config.containsKey("max_tokens")
                ? ((Number) config.get("max_tokens")).intValue() : null;

        // 변수 치환
        String prompt = context.resolve(promptTemplate);
        String system = withSkills(systemTemplate != null ? context.resolve(systemTemplate) : null, step, context);

        // connection_id가 있으면 ConnectionAdapterFactory 사용, 없으면 ModelRouter 폴백
        LLMAdapter adapter;
        String resolvedModel;
        if (connectionId != null && !connectionId.isBlank()) {
            adapter = connectionAdapterFactory.getAdapter(connectionId);
            resolvedModel = connectionAdapterFactory.resolveModel(connectionId, model);
        } else {
            resolvedModel = modelRouter.resolveModelId(model);
            adapter = modelRouter.route(new LLMRequest(resolvedModel, List.of()));
        }

        // CR-159: 단계에 붙인 사진, PDF 를 프롬프트와 같은 사용자 메시지의 그림, 문서 블록으로 보낸다.
        StepAttachmentResolver.Resolved resolvedAttachments = resolveAttachments(step, context, adapter);
        List<ContentBlock> attachments = resolvedAttachments.blocks();
        String recordedPrompt = resolvedAttachments.isEmpty() ? prompt : prompt + "\n\n" + resolvedAttachments.summary();

        // CR-007: response_schema
        @SuppressWarnings("unchecked")
        Map<String, Object> responseSchema = config.containsKey("response_schema")
                ? (Map<String, Object>) config.get("response_schema") : null;

        // CR-030: step config에서 Extended Thinking 설정 추출
        Boolean extThinking = config.containsKey("extended_thinking")
                ? Boolean.valueOf(config.get("extended_thinking").toString()) : null;
        Integer thinkingBudget = config.containsKey("thinking_budget_tokens")
                ? ((Number) config.get("thinking_budget_tokens")).intValue() : null;

        // CR-085 P3: opt-in 노드 내부 토큰 스트리밍. response_schema 있으면 구조화 출력이라
        // 토큰 단위 의미가 없고 자동분할(Phase 3)과 충돌 → 텍스트 응답일 때만 스트리밍.
        boolean streamTokens = Boolean.parseBoolean(
                String.valueOf(config.getOrDefault("stream_tokens", "false")))
                && (responseSchema == null || responseSchema.isEmpty())
                && eventPublisher != null;

        // ── Phase 1: 일반 호출 ──
        // max_tokens 지정 = 소비앱이 출력 크기를 선언한 것 → 그 값으로 1차 호출(에스컬레이션 왕복 제거).
        // 미지정 = 크기 미상 → 4096 부터 시작해 잘릴 때만 Phase 2/3 로 올림(비용 절감).
        int phase1Tokens = configCeiling != null ? configCeiling : INITIAL_MAX_TOKENS;
        // 입력은 호출 전에 확정된 값 → 응답을 기다리지 않고 즉시 적재 (응답 생성 중에도 화면에 입력 표시).
        recordLlmRequest(context, step.id(), resolvedModel, system, recordedPrompt, connectionId);
        long phase1Start = System.currentTimeMillis();
        LLMResponse response = streamTokens
                ? callLlmStreaming(adapter, resolvedModel, system, prompt, attachments, phase1Tokens, context,
                        extThinking, thinkingBudget, step.id())
                : callLlm(adapter, resolvedModel, system, prompt, attachments, responseSchema,
                        phase1Tokens, context, extThinking, thinkingBudget);
        // CR-090: LLM_RESPONSE (Phase 1) / CR-102: prompt·response 본문 + connection 메타
        recordLlmResponse(context, step.id(), response, resolvedModel, System.currentTimeMillis() - phase1Start, system, recordedPrompt, connectionId);

        if (response.finishReason() != LLMResponse.FinishReason.MAX_TOKENS) {
            response = ensureStructuredOutput(step.id(), adapter, resolvedModel, system, prompt, attachments, responseSchema,
                    phase1Tokens, context, extThinking, thinkingBudget, connectionId, response);
            return buildResult(response, resolvedModel);
        }

        log.warn("LLM_CALL step '{}': Phase 1 응답 잘림 (max_tokens={}, output_tokens={}). 에스컬레이션 시도...",
                step.id(), phase1Tokens, response.usage().outputTokens());

        // ── Phase 2: 에스컬레이션 (1회) ──
        int phase2Tokens = configCeiling != null ? Math.min(ESCALATION_MAX_TOKENS, configCeiling) : ESCALATION_MAX_TOKENS;
        if (phase2Tokens > phase1Tokens) {
            long phase2Start = System.currentTimeMillis();
            response = callLlm(adapter, resolvedModel, system, prompt, attachments, responseSchema, phase2Tokens, context,
                    null, null);
            // CR-090: LLM_RESPONSE (Phase 2) / CR-102: prompt·response 본문 + connection 메타
            recordLlmResponse(context, step.id(), response, resolvedModel, System.currentTimeMillis() - phase2Start, system, recordedPrompt, connectionId);

            if (response.finishReason() != LLMResponse.FinishReason.MAX_TOKENS) {
                log.info("LLM_CALL step '{}': 에스컬레이션 성공 (max_tokens={})", step.id(), phase2Tokens);
                response = ensureStructuredOutput(step.id(), adapter, resolvedModel, system, prompt, attachments, responseSchema,
                        phase2Tokens, context, null, null, connectionId, response);
                return buildResult(response, resolvedModel);
            }

            log.warn("LLM_CALL step '{}': Phase 2 에스컬레이션도 잘림 (max_tokens={}, output_tokens={})",
                    step.id(), phase2Tokens, response.usage().outputTokens());
        }

        // ── Phase 3: 자동분할 ──
        if (responseSchema == null || responseSchema.isEmpty()) {
            throw new RuntimeException(
                    "LLM_CALL step '" + step.id() + "': 응답이 max_tokens에서 잘렸으나 " +
                    "response_schema가 없어 자동분할을 수행할 수 없습니다. " +
                    "step config에 max_tokens를 늘리거나 스텝을 수동 분할하세요.");
        }

        log.info("LLM_CALL step '{}': 자동분할 모드 진입", step.id());
        return executeAutoSplit(step.id(), adapter, resolvedModel, system, prompt, attachments, responseSchema, context);
    }

    // ═══════════════════════════════════════════════
    // 자동분할 (Phase 3)
    // ═══════════════════════════════════════════════

    private Map<String, Object> executeAutoSplit(
            String stepId, LLMAdapter adapter, String resolvedModel,
            String system, String prompt, List<ContentBlock> attachments,
            Map<String, Object> responseSchema, StepContext context) {

        // 3-a. 분할 계획 호출
        int[] planTokenUsage = new int[2]; // [inputTokens, outputTokens]
        List<Map<String, Object>> parts = planSplit(stepId, adapter, resolvedModel, system, prompt,
                responseSchema, context, planTokenUsage);
        log.info("LLM_CALL step '{}': {}개 파트로 분할 계획 수립", stepId, parts.size());

        // 3-b. 파트별 실행 (실패 시 1회 재시도)
        List<String> partResults = new ArrayList<>();
        int totalInputTokens = planTokenUsage[0];
        int totalOutputTokens = planTokenUsage[1];

        for (int i = 0; i < parts.size(); i++) {
            Map<String, Object> part = parts.get(i);
            String scope = (String) part.getOrDefault("scope", part.getOrDefault("description", "파트 " + (i + 1)));

            LLMResponse partResponse = executePart(stepId, adapter, resolvedModel, system, prompt, attachments,
                    scope, i, parts.size(), context);

            String partOutput = partResponse.textContent();
            partResults.add(partOutput);
            totalInputTokens += partResponse.usage().inputTokens();
            totalOutputTokens += partResponse.usage().outputTokens();

            log.info("LLM_CALL step '{}': 파트 {}/{} 완료 ({}자)", stepId, i + 1, parts.size(), partOutput.length());
        }

        // 3-c. 취합 호출
        Map<String, Object> mergedResult = mergeResults(stepId, adapter, resolvedModel,
                partResults, responseSchema, context);

        totalInputTokens += (int) mergedResult.getOrDefault("_merge_input_tokens", 0);
        totalOutputTokens += (int) mergedResult.getOrDefault("_merge_output_tokens", 0);
        mergedResult.remove("_merge_input_tokens");
        mergedResult.remove("_merge_output_tokens");

        log.info("LLM_CALL step '{}': 자동분할 완료 ({}개 파트, 총 input={}, output={})",
                stepId, parts.size(), totalInputTokens, totalOutputTokens);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("output", "");
        result.put("structured_data", mergedResult);
        result.put("model", resolvedModel);
        result.put("input_tokens", totalInputTokens);
        result.put("output_tokens", totalOutputTokens);
        result.put("_auto_split", true);
        result.put("_split_parts", parts.size());
        return result;
    }

    /**
     * 3-a. 분할 계획: LLM에게 작업을 N개 파트로 나누도록 요청
     */
    /**
     * 3-b. 파트 실행: 실패 시 1회 재시도
     */
    private LLMResponse executePart(
            String stepId, LLMAdapter adapter, String resolvedModel,
            String system, String prompt, List<ContentBlock> attachments, String scope,
            int partIndex, int totalParts, StepContext context) {

        // CR-036: DB 외부화
        String partTemplate = promptTemplateService.getTemplateOrFallback("workflow.split.part_prompt",
                "Generate only the following scope from the overall task.\n\n=== Scope ===\n{{scope}}\n\n=== Original Request ===\n{{prompt}}\n\nRespond in JSON format only.");
        String partPrompt = promptTemplateService.renderTemplate(partTemplate, Map.of("scope", scope, "prompt", prompt));

        for (int attempt = 1; attempt <= 2; attempt++) {
            LLMResponse partResponse = callLlm(adapter, resolvedModel, system, partPrompt, attachments,
                    null, SPLIT_PART_MAX_TOKENS, context, null, null);

            if (partResponse.finishReason() != LLMResponse.FinishReason.MAX_TOKENS) {
                return partResponse;
            }

            if (attempt == 1) {
                log.warn("LLM_CALL step '{}': 파트 {}/{} 잘림, 1회 재시도...",
                        stepId, partIndex + 1, totalParts);
            } else {
                throw new RuntimeException(
                        "LLM_CALL step '" + stepId + "': 자동분할 파트 " + (partIndex + 1) + "/" + totalParts +
                        " 재시도 후에도 잘림. 워크플로우 스텝을 수동으로 분할하세요.");
            }
        }

        throw new IllegalStateException("unreachable");
    }

    /**
     * 3-a. 분할 계획: LLM에게 작업을 N개 파트로 나누도록 요청
     */
    private List<Map<String, Object>> planSplit(
            String stepId, LLMAdapter adapter, String resolvedModel,
            String system, String prompt, Map<String, Object> responseSchema,
            StepContext context, int[] tokenUsageOut) {

        String schemaStr;
        try {
            schemaStr = MAPPER.writeValueAsString(responseSchema);
        } catch (Exception e) {
            schemaStr = responseSchema.toString();
        }

        // CR-036: DB 외부화
        String planSystem = promptTemplateService.getTemplateOrFallback("workflow.split.plan_system",
                "You are an expert at splitting large JSON generation tasks. Divide the task into independently generable parts.");

        String planTemplate = promptTemplateService.getTemplateOrFallback("workflow.split.plan_prompt",
                "The output of the following task is too large to generate at once.\nCreate a split plan into independently generable parts.\n\n=== Original System Prompt ===\n{{system}}\n\n=== Original Request ===\n{{prompt}}\n\n=== Output Schema ===\n{{schema}}\n\nRules:\n- Each part must be generable within 3000 tokens\n- Number of parts: 2 to {{max_parts}}\n- Each part's scope must be specific and clear\n- Part results must be mergeable into the final schema");
        String planPrompt = promptTemplateService.renderTemplate(planTemplate, Map.of(
                "system", system != null ? system : "(none)",
                "prompt", prompt,
                "schema", schemaStr,
                "max_parts", String.valueOf(SPLIT_MAX_PARTS)));

        Map<String, Object> planSchema = Map.of(
                "type", "object",
                "required", List.of("parts"),
                "properties", Map.of(
                        "parts", Map.of(
                                "type", "array",
                                "items", Map.of(
                                        "type", "object",
                                        "required", List.of("part_number", "scope"),
                                        "properties", Map.of(
                                                "part_number", Map.of("type", "integer"),
                                                "scope", Map.of("type", "string"),
                                                "description", Map.of("type", "string")
                                        )
                                )
                        )
                )
        );

        LLMResponse planResponse = callLlm(adapter, resolvedModel, planSystem, planPrompt,
                planSchema, SPLIT_PART_MAX_TOKENS, context);

        // 분할 계획 호출 토큰 집계
        tokenUsageOut[0] = planResponse.usage().inputTokens();
        tokenUsageOut[1] = planResponse.usage().outputTokens();

        // structured_data에서 parts 추출
        Map<String, Object> planData = extractStructuredData(planResponse);
        if (planData == null || !planData.containsKey("parts")) {
            throw new RuntimeException(
                    "LLM_CALL step '" + stepId + "': 자동분할 계획 생성 실패 — parts 배열을 받지 못했습니다.");
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> parts = (List<Map<String, Object>>) planData.get("parts");

        if (parts.isEmpty()) {
            throw new RuntimeException(
                    "LLM_CALL step '" + stepId + "': 자동분할 계획이 빈 배열입니다.");
        }
        if (parts.size() > SPLIT_MAX_PARTS) {
            log.warn("LLM_CALL step '{}': 분할 계획이 {}개 → {}개로 제한", stepId, parts.size(), SPLIT_MAX_PARTS);
            parts = parts.subList(0, SPLIT_MAX_PARTS);
        }

        return parts;
    }

    /**
     * 3-c. 취합: 파트별 결과를 원본 schema에 맞게 병합
     */
    private Map<String, Object> mergeResults(
            String stepId, LLMAdapter adapter, String resolvedModel,
            List<String> partResults, Map<String, Object> responseSchema, StepContext context) {

        String schemaStr;
        try {
            schemaStr = MAPPER.writeValueAsString(responseSchema);
        } catch (Exception e) {
            schemaStr = responseSchema.toString();
        }

        StringBuilder fragments = new StringBuilder();
        for (int i = 0; i < partResults.size(); i++) {
            fragments.append("=== 파트 ").append(i + 1).append(" ===\n");
            fragments.append(partResults.get(i)).append("\n\n");
        }

        String mergeSystem = promptTemplateService.getTemplateOrFallback("workflow.split.merge_system",
                "You are an expert at merging JSON fragments into a single complete structure. Generate one JSON that exactly matches the given schema.");

        String mergeTemplate = promptTemplateService.getTemplateOrFallback("workflow.split.merge_prompt",
                "Merge the following fragments into one complete JSON according to the schema below.\n\n=== Target Schema ===\n{{schema}}\n\n=== Fragments ===\n{{fragments}}\n\nRules:\n- Include all content from every fragment without omission\n- Rearrange into the structure matching the schema\n- Remove duplicates but do not omit any content");
        String mergePrompt = promptTemplateService.renderTemplate(mergeTemplate, Map.of(
                "schema", schemaStr,
                "fragments", fragments.toString()));

        // 취합: 4096 시도 → 잘리면 8192 에스컬레이션
        LLMResponse mergeResponse = callLlm(adapter, resolvedModel, mergeSystem, mergePrompt,
                responseSchema, SPLIT_PART_MAX_TOKENS, context);

        if (mergeResponse.finishReason() == LLMResponse.FinishReason.MAX_TOKENS) {
            log.warn("LLM_CALL step '{}': 취합 잘림 (max_tokens={}), 에스컬레이션...",
                    stepId, SPLIT_PART_MAX_TOKENS);
            mergeResponse = callLlm(adapter, resolvedModel, mergeSystem, mergePrompt,
                    responseSchema, ESCALATION_MAX_TOKENS, context);
        }

        if (mergeResponse.finishReason() == LLMResponse.FinishReason.MAX_TOKENS) {
            throw new RuntimeException(
                    "LLM_CALL step '" + stepId + "': 자동분할 취합 에스컬레이션 후에도 잘림. " +
                    "파트가 너무 많거나 각 파트 결과가 큽니다. 워크플로우 스텝을 수동으로 분할하세요.");
        }

        Map<String, Object> merged = extractStructuredData(mergeResponse);
        if (merged == null) {
            // structured_data 없으면 text 응답을 JSON 파싱 시도
            String text = mergeResponse.textContent();
            try {
                merged = MAPPER.readValue(text, new TypeReference<>() {});
            } catch (Exception e) {
                throw new RuntimeException(
                        "LLM_CALL step '" + stepId + "': 자동분할 취합 결과를 JSON으로 파싱할 수 없습니다: " + e.getMessage());
            }
        }

        merged = new LinkedHashMap<>(merged);
        merged.put("_merge_input_tokens", mergeResponse.usage().inputTokens());
        merged.put("_merge_output_tokens", mergeResponse.usage().outputTokens());
        return merged;
    }

    // ═══════════════════════════════════════════════
    // 공통 유틸리티
    // ═══════════════════════════════════════════════

    /**
     * LLM 단일 호출 — 어댑터/모델/메시지/토큰을 받아 호출
     */
    /** 하위 호환: thinking 설정 없는 호출 */
    private LLMResponse callLlm(LLMAdapter adapter, String resolvedModel,
                                 String system, String prompt,
                                 Map<String, Object> responseSchema,
                                 int maxTokens, StepContext context) {
        return callLlm(adapter, resolvedModel, system, prompt, List.of(), responseSchema,
                maxTokens, context, null, null);
    }

    /**
     * CR-085 P3: 공용 {@link LLMAdapter#chatStream} 콜백을 재사용해 토큰 델타를
     * {@code WorkflowEventPublisher.stepToken} 으로 발행하면서 전체 응답을 재조립한다.
     *
     * <p>orchestrator(ChatController SSE)의 스트리밍 경로와 동일한 chatStream 공용 API 를
     * 재사용 — 별도 스트리밍 구현 없음 (설계 §2.3 "중복 구현 금지"). 응답 재조립 결과는
     * 기존 동기 {@link #callLlm} 과 동일 형태({@link LLMResponse})라 이후 Phase 2/3 분기 무영향.
     *
     * <p>iterationIndex 는 null 고정 — StepExecutor 인터페이스가 cyclic 회차를 전달하지 않으므로
     * (설계의 DAG/기존=null 계약과 호환). cyclic 회차 정밀 매핑은 알려진 한계.
     */
    private LLMResponse callLlmStreaming(LLMAdapter adapter, String resolvedModel,
                                         String system, String prompt, List<ContentBlock> attachments,
                                         int maxTokens, StepContext context,
                                         Boolean extendedThinking, Integer thinkingBudgetTokens,
                                         String stepId) {
        List<UnifiedMessage> messages = baseMessages(system, prompt, attachments);

        ModelConfig modelConfig = new ModelConfig(null, maxTokens, null, null,
                extendedThinking, thinkingBudgetTokens);
        LLMRequest request = new LLMRequest(resolvedModel, messages, null,
                modelConfig, true, context.workflowRunId(), null, null);

        StringBuilder textBuf = new StringBuilder();
        java.util.concurrent.atomic.AtomicReference<TokenUsage> usageRef = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<LLMResponse.FinishReason> finishRef =
                new java.util.concurrent.atomic.AtomicReference<>(LLMResponse.FinishReason.END);
        String[] idRef = {""};
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> errRef = new java.util.concurrent.atomic.AtomicReference<>();

        UUID runId = parseUuid(context.workflowRunId());
        try {
            // CR-045 패턴: chatStream 은 fire-and-forget(가상 스레드) → CountDownLatch 로 완료 대기.
            adapter.chatStream(request, chunk -> {
                try {
                    if (idRef[0].isEmpty() && chunk.id() != null) idRef[0] = chunk.id();
                    if (chunk.done()) {
                        if (chunk.usage() != null) usageRef.set(chunk.usage());
                        if (chunk.finishReason() != null) finishRef.set(chunk.finishReason());
                        latch.countDown();
                        return;
                    }
                    if (chunk.delta() == null) return;
                    if (!"thinking".equals(chunk.type())) {
                        textBuf.append(chunk.delta());  // thinking 델타는 본문 재조립에서 제외 (기존 buildResult 와 동일)
                    }
                    // opt-in 토큰 이벤트 발행 (iterationIndex=null — 인터페이스 미전달)
                    eventPublisher.stepToken(runId, null, stepId, null,
                            chunk.delta(), chunk.type() != null ? chunk.type() : "text");
                } catch (Exception inner) {
                    errRef.set(inner);
                    latch.countDown();
                }
            });
            // 스텝 timeout 과 별개 — 합리적 상한 (Phase 1 max_tokens 기준). 미완료 시 폴백.
            if (!latch.await(300, java.util.concurrent.TimeUnit.SECONDS)) {
                log.warn("LLM_CALL step '{}': 토큰 스트리밍 타임아웃 — 부분 응답으로 진행", stepId);
            }
        } catch (Exception e) {
            throw new RuntimeException("LLM streaming call failed: " + e.getMessage(), e);
        }
        if (errRef.get() != null) {
            throw new RuntimeException("LLM streaming call failed: " + errRef.get().getMessage(), errRef.get());
        }

        TokenUsage usage = usageRef.get() != null ? usageRef.get() : new TokenUsage(0, 0);
        return new LLMResponse(
                idRef[0].isEmpty() ? UUID.randomUUID().toString() : idRef[0],
                resolvedModel,
                List.of(new ContentBlock.Text(textBuf.toString())),
                null,            // toolCalls — 스트리밍 텍스트 응답엔 도구 없음
                usage,
                finishRef.get(),
                0L,              // latencyMs (스트리밍은 누적 측정 안 함)
                0.0);            // costUsd
    }

    private static UUID parseUuid(String s) {
        try {
            return s != null ? UUID.fromString(s) : null;
        } catch (IllegalArgumentException e) {
            return null;  // 테스트/비표준 runId — 이벤트 runId=null 허용
        }
    }

    /** CR-102: 프롬프트 입력(system + prompt) 본문 조립 — LLM_REQUEST/LLM_RESPONSE 적재 공용. */
    private static String buildPromptBody(String system, String prompt) {
        return (system != null && !system.isBlank())
                ? "[SYSTEM]\n" + system + "\n\n[PROMPT]\n" + prompt
                : prompt;
    }

    /**
     * 입력 프롬프트(LLM_REQUEST) 적재 — Phase 1 메인 호출 직전 1회.
     *
     * <p>입력은 호출 전에 확정된 값이라 응답을 기다리지 않고 즉시 적재 → 응답 생성 중에도 화면에 입력 표시.
     * 자동분할 plan/part/merge 까지 매번 적재하면 노이즈가 커서 메인 호출 1건만 발행한다.
     */
    private void recordLlmRequest(StepContext context, String stepId, String resolvedModel,
                                  String system, String prompt, String connectionId) {
        if (eventRecorder == null) return;
        UUID runId = parseUuid(context.workflowRunId());
        if (runId == null) return;
        eventRecorder.llmRequest(runId, stepId, null, resolvedModel, buildPromptBody(system, prompt), connectionId);
    }

    /**
     * CR-090: LLM_RESPONSE 이벤트 발행 헬퍼.
     *
     * <p>Phase 1/2/3(자동분할의 plan/part/merge)에서 모두 호출 — 단일 LLM 호출 1회당 1 이벤트.
     */
    private void recordLlmResponse(StepContext context, String stepId, LLMResponse response,
                                   String resolvedModel, long durationMs,
                                   String system, String prompt, String connectionId) {
        if (eventRecorder == null || response == null) return;
        UUID runId = parseUuid(context.workflowRunId());
        if (runId == null) return;
        int in = response.usage() != null ? response.usage().inputTokens() : 0;
        int out = response.usage() != null ? response.usage().outputTokens() : 0;
        String finishReason = response.finishReason() != null ? response.finishReason().name() : null;
        // CR-102: 프롬프트 입력(system + prompt) ↔ 응답 본문 전문 적재 (품질 정독용)
        String promptBody = buildPromptBody(system, prompt);
        String responseBody = response.textContent();
        // 구조화 출력(response_schema)이면 응답이 tool_use 블록이라 textContent 가 빈 문자열 —
        // structured_data 를 직렬화해 본문으로 적재 (안 하면 정독 불가).
        if (responseBody == null || responseBody.isBlank()) {
            Map<String, Object> structured = extractStructuredData(response);
            if (structured != null) {
                try {
                    responseBody = MAPPER.writeValueAsString(structured);
                } catch (Exception e) {
                    responseBody = String.valueOf(structured);
                }
            }
        }
        eventRecorder.llmResponse(runId, stepId, null, resolvedModel, in, out, finishReason, durationMs,
                null, null, promptBody, responseBody, connectionId);
        // CR-102: CLI 어댑터 경로 — CLI 가 내부에서 돈 도구 루프 관찰을 TOOL_USE/TOOL_RESULT 로 적재
        if (response.hasObservedToolEvents()) {
            eventRecorder.observedTools(runId, stepId, null, response.observedToolEvents());
        }
    }

    private LLMResponse callLlm(LLMAdapter adapter, String resolvedModel,
                                 String system, String prompt, List<ContentBlock> attachments,
                                 Map<String, Object> responseSchema,
                                 int maxTokens, StepContext context,
                                 Boolean extendedThinking, Integer thinkingBudgetTokens) {
        return callLlm(adapter, resolvedModel, baseMessages(system, prompt, attachments), responseSchema,
                maxTokens, context, extendedThinking, thinkingBudgetTokens);
    }

    private static List<UnifiedMessage> baseMessages(String system, String prompt, List<ContentBlock> attachments) {
        List<UnifiedMessage> messages = new ArrayList<>();
        if (system != null && !system.isBlank()) {
            messages.add(UnifiedMessage.ofText(UnifiedMessage.Role.SYSTEM, system));
        }
        if (attachments.isEmpty()) {
            messages.add(UnifiedMessage.ofText(UnifiedMessage.Role.USER, prompt));
        } else {
            // 그림을 글보다 앞에 둔다(Anthropic 권장 순서).
            List<ContentBlock> content = new ArrayList<>(attachments);
            content.add(new ContentBlock.Text(prompt));
            messages.add(UnifiedMessage.ofUserContent(content));
        }
        return messages;
    }

    private StepAttachmentResolver.Resolved resolveAttachments(WorkflowStep step, StepContext context, LLMAdapter adapter) {
        Object ref = step.config().get("attachments");
        if (ref == null) return StepAttachmentResolver.Resolved.EMPTY;
        if (attachmentResolver == null) {
            throw new IllegalStateException("LLM_CALL step '" + step.id() + "': attachments 를 읽을 수 없다(첨부 해석기 없음)");
        }
        return attachmentResolver.resolve(step.id(), ref, context, adapter.capabilities());
    }

    private LLMResponse callLlm(LLMAdapter adapter, String resolvedModel,
                                 List<UnifiedMessage> messages,
                                 Map<String, Object> responseSchema,
                                 int maxTokens, StepContext context,
                                 Boolean extendedThinking, Integer thinkingBudgetTokens) {
        ModelConfig modelConfig = new ModelConfig(null, maxTokens, null, null,
                extendedThinking, thinkingBudgetTokens);
        LLMRequest request = new LLMRequest(resolvedModel, messages, null,
                modelConfig, false, context.workflowRunId(), null, responseSchema);

        try {
            return adapter.chat(request).get();
        } catch (Exception e) {
            throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
        }
    }

    /**
     * CR-143: BIZ-034 를 워크플로우 LLM_CALL 에도 적용한다. response_schema 를 선언했으면 스키마를 만족하는
     * 결과만 넘기고, 못 하면 같은 대화에 직전 응답과 교정 지시를 붙여 최대 2회 다시 요청한 뒤 스텝을 실패시킨다.
     *
     * <p>어댑터 출구 정규화(CR-113)는 JSON 이 깨져도 원문을 그대로 돌려준다. 여기서 거르지 않으면 run 이
     * 결과 없이 completed 로 끝나 소비앱이 빈 결과를 성공으로 받는다. CLI 경로는 워커가 마지막 USER 만 보내지만
     * 같은 run 의 CLI 세션이 앞 대화를 가지고 있어 교정 지시만으로 맥락이 이어진다.
     */
    private LLMResponse ensureStructuredOutput(String stepId, LLMAdapter adapter, String resolvedModel,
                                               String system, String prompt, List<ContentBlock> attachments,
                                               Map<String, Object> responseSchema,
                                               int maxTokens, StepContext context,
                                               Boolean extendedThinking, Integer thinkingBudgetTokens,
                                               String connectionId, LLMResponse response) {
        if (responseSchema == null || responseSchema.isEmpty()) {
            return response;
        }
        List<UnifiedMessage> messages = baseMessages(system, prompt, attachments);
        LLMResponse current = response;
        String problem = structuredOutputProblem(current, responseSchema);
        for (int retry = 1; problem != null && retry <= STRUCTURED_OUTPUT_MAX_RETRIES; retry++) {
            log.warn("LLM_CALL step '{}': 구조화 출력 불충족 ({}). 재요청 {}/{}",
                    stepId, problem, retry, STRUCTURED_OUTPUT_MAX_RETRIES);
            String correction = "Your previous response did not satisfy the required output format: " + problem
                    + "\nReturn the same content again as one valid JSON object that matches the requested schema."
                    + " Inside string values, escape double quotes as \\\" and line breaks as \\n. Output only the JSON.";
            messages.add(UnifiedMessage.ofText(UnifiedMessage.Role.ASSISTANT, echoOf(current)));
            messages.add(UnifiedMessage.ofText(UnifiedMessage.Role.USER, correction));
            long start = System.currentTimeMillis();
            current = callLlm(adapter, resolvedModel, List.copyOf(messages), responseSchema, maxTokens, context,
                    extendedThinking, thinkingBudgetTokens);
            recordLlmResponse(context, stepId, current, resolvedModel, System.currentTimeMillis() - start,
                    system, correction, connectionId);
            problem = structuredOutputProblem(current, responseSchema);
        }
        if (problem != null) {
            throw new RuntimeException("LLM_CALL step '" + stepId + "': response_schema 를 만족하는 결과를 받지 못함 "
                    + "(BIZ-034, 재요청 " + STRUCTURED_OUTPUT_MAX_RETRIES + "회 후): " + problem);
        }
        return current;
    }

    /** 스키마를 만족하면 null, 아니면 모델에게 그대로 돌려줄 수 있는 실패 사유. */
    private String structuredOutputProblem(LLMResponse response, Map<String, Object> responseSchema) {
        Map<String, Object> data = extractStructuredData(response);
        if (data == null) {
            return "the response could not be parsed as a JSON object (" + jsonParseError(response.textContent()) + ")";
        }
        SchemaValidator.ValidationResult result = schemaValidator.validate(responseSchema, data);
        return result.valid() ? null : "schema validation failed " + result.errors();
    }

    private static String jsonParseError(String text) {
        if (text == null || text.isBlank()) {
            return "empty response";
        }
        try {
            MAPPER.readValue(StructuredOutputNormalizer.stripFence(text), new TypeReference<Map<String, Object>>() {});
            return "not a JSON object";
        } catch (JsonProcessingException e) {
            var loc = e.getLocation();
            return e.getOriginalMessage()
                    + (loc != null ? " at line " + loc.getLineNr() + ", column " + loc.getColumnNr() : "");
        }
    }

    /** 재요청 이력에 넣을 직전 응답. 텍스트가 없으면(Anthropic 경로의 Structured 단독) 데이터를 JSON 으로 적는다. */
    private static String echoOf(LLMResponse response) {
        String text = response.textContent();
        if (text != null && !text.isBlank()) {
            return text;
        }
        Map<String, Object> data = response.content().stream()
                .filter(b -> b instanceof ContentBlock.Structured)
                .map(b -> ((ContentBlock.Structured) b).data())
                .findFirst().orElse(null);
        try {
            return data != null ? MAPPER.writeValueAsString(data) : "";
        } catch (JsonProcessingException e) {
            return String.valueOf(data);
        }
    }

    /**
     * LLMResponse에서 structured_data 추출
     */
    private Map<String, Object> extractStructuredData(LLMResponse response) {
        return response.content().stream()
                .filter(b -> b instanceof ContentBlock.Structured)
                .map(b -> ((ContentBlock.Structured) b).data())
                .findFirst().orElse(null);
    }

    /**
     * LLMResponse → 표준 결과 Map 변환
     */
    private Map<String, Object> buildResult(LLMResponse response, String resolvedModel) {
        String output = response.textContent();
        Map<String, Object> structuredData = extractStructuredData(response);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("output", output);
        if (structuredData != null) {
            result.put("structured_data", structuredData);
        }
        result.put("model", resolvedModel);
        result.put("input_tokens", response.usage().inputTokens());
        result.put("output_tokens", response.usage().outputTokens());
        return result;
    }
}
