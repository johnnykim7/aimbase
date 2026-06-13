package com.platform.orchestrator;

import com.platform.action.ActionExecutor;
import com.platform.action.model.*;
import com.platform.hook.HookDecision;
import com.platform.hook.HookDispatcher;
import com.platform.hook.HookEvent;
import com.platform.hook.HookInput;
import com.platform.hook.HookOutput;
import com.platform.domain.UsageLogEntity;
import com.platform.llm.ConnectionAdapterFactory;
import com.platform.llm.ConnectionGroupSelector;
import com.platform.llm.LLMAdapterRegistry;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.model.*;
import com.platform.llm.router.FallbackChainExecutor;
import com.platform.llm.router.ModelRouter;
import com.platform.policy.AuditLogger;
import com.platform.policy.MCPSafetyClient;
import com.platform.policy.PolicyEngine;
import com.platform.policy.model.PolicyResult;
import com.platform.monitoring.PlatformMetrics;
import com.platform.monitoring.TraceService;
import com.platform.rag.RAGService;
import com.platform.repository.UsageLogRepository;
import com.platform.schema.SchemaRegistry;
import com.platform.schema.SchemaValidator;
import com.platform.session.CancellationRegistry;
import com.platform.session.ContextWindowManager;
import com.platform.session.MemoryAutoExtractService;
import com.platform.session.MemoryService;
import com.platform.session.ResponseCacheService;
import com.platform.session.SessionStore;
import com.platform.tenant.TenantContext;
import com.platform.tenant.quota.QuotaService;
import com.platform.context.AssemblyResult;
import com.platform.context.ContextAssemblyEngine;
import com.platform.runtime.RuntimeRegistry;
import com.platform.tool.ApprovalState;
import com.platform.tool.PermissionLevel;
import com.platform.tool.ToolCallHandler;
import com.platform.tool.ToolContext;
import com.platform.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 전체 요청 흐름을 관리하는 최상위 컴포넌트.
 *
 * Phase 1 흐름:
 * 1. 세션 컨텍스트 로드
 * 2. ModelRouter → LLM 선택
 * 3. LLM 호출
 * 4. LLM 응답 처리
 * 5. 응답 반환 + 세션 저장 + 사용량 로깅
 *
 * Phase 2 추가: actionsEnabled=true 시 ToolCallHandler → Tool Use 루프 실행
 * Phase 3 추가: RAG Retriever → 컨텍스트 주입
 */
@Component
public class OrchestratorEngine {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorEngine.class);

    private final ModelRouter modelRouter;
    private final FallbackChainExecutor fallbackChainExecutor;
    private final ConnectionAdapterFactory connectionAdapterFactory;
    private final ConnectionGroupSelector connectionGroupSelector;
    private final SessionStore sessionStore;
    private final ContextWindowManager contextWindowManager;
    private final PolicyEngine policyEngine;
    private final ActionExecutor actionExecutor;
    private final AuditLogger auditLogger;
    private final UsageLogRepository usageLogRepository;
    private final ToolCallHandler toolCallHandler;
    private final ToolRegistry toolRegistry;
    private final RAGService ragService;
    private final MCPSafetyClient mcpSafetyClient;
    private final PlatformMetrics platformMetrics;
    private final TraceService traceService;
    private final SchemaRegistry schemaRegistry;
    private final SchemaValidator schemaValidator;
    private final ObjectMapper objectMapper;
    private final ResponseCacheService responseCacheService;
    private final MemoryService memoryService;
    private final QuotaService quotaService;
    private final ContextAssemblyEngine contextAssemblyEngine;
    private final RuntimeRegistry runtimeRegistry;
    private final HookDispatcher hookDispatcher;
    private final MemoryAutoExtractService memoryAutoExtractService;
    private final CancellationRegistry cancellationRegistry;

    private static final int STRUCTURED_OUTPUT_MAX_RETRIES = 2;

    public OrchestratorEngine(
            ModelRouter modelRouter,
            FallbackChainExecutor fallbackChainExecutor,
            ConnectionAdapterFactory connectionAdapterFactory,
            ConnectionGroupSelector connectionGroupSelector,
            SessionStore sessionStore,
            ContextWindowManager contextWindowManager,
            PolicyEngine policyEngine,
            ActionExecutor actionExecutor,
            AuditLogger auditLogger,
            UsageLogRepository usageLogRepository,
            ToolCallHandler toolCallHandler,
            ToolRegistry toolRegistry,
            RAGService ragService,
            MCPSafetyClient mcpSafetyClient,
            PlatformMetrics platformMetrics,
            TraceService traceService,
            SchemaRegistry schemaRegistry,
            SchemaValidator schemaValidator,
            ObjectMapper objectMapper,
            ResponseCacheService responseCacheService,
            MemoryService memoryService,
            QuotaService quotaService,
            ContextAssemblyEngine contextAssemblyEngine,
            RuntimeRegistry runtimeRegistry,
            HookDispatcher hookDispatcher,
            MemoryAutoExtractService memoryAutoExtractService,
            CancellationRegistry cancellationRegistry
    ) {
        this.modelRouter = modelRouter;
        this.fallbackChainExecutor = fallbackChainExecutor;
        this.connectionAdapterFactory = connectionAdapterFactory;
        this.connectionGroupSelector = connectionGroupSelector;
        this.sessionStore = sessionStore;
        this.contextWindowManager = contextWindowManager;
        this.policyEngine = policyEngine;
        this.actionExecutor = actionExecutor;
        this.auditLogger = auditLogger;
        this.usageLogRepository = usageLogRepository;
        this.toolCallHandler = toolCallHandler;
        this.toolRegistry = toolRegistry;
        this.ragService = ragService;
        this.mcpSafetyClient = mcpSafetyClient;
        this.platformMetrics = platformMetrics;
        this.traceService = traceService;
        this.schemaRegistry = schemaRegistry;
        this.schemaValidator = schemaValidator;
        this.objectMapper = objectMapper;
        this.responseCacheService = responseCacheService;
        this.memoryService = memoryService;
        this.quotaService = quotaService;
        this.contextAssemblyEngine = contextAssemblyEngine;
        this.runtimeRegistry = runtimeRegistry;
        this.hookDispatcher = hookDispatcher;
        this.memoryAutoExtractService = memoryAutoExtractService;
        this.cancellationRegistry = cancellationRegistry;
    }

    /**
     * 동기 채팅 요청 처리
     */
    public ChatResponse chat(ChatRequest request) {
        String sessionId = request.sessionId() != null
                ? request.sessionId()
                : UUID.randomUUID().toString();

        // CR-047 PRD-299: Memory prefetch — assemble() 합류 전까지 백그라운드 진행
        contextAssemblyEngine.prefetchMemory(sessionId, request.userId());

        // 0. CR-013: LLM 토큰 쿼터 체크 (월간 한도 초과 시 즉시 거부)
        String tenantId = TenantContext.getTenantId();
        if (tenantId != null) {
            quotaService.checkLLMQuota(tenantId, 1000).throwIfExceeded();
        }

        // CR-030 PRD-194: SessionStart 훅 (세션이 새로 생성된 경우)
        if (request.sessionId() == null) {
            hookDispatcher.dispatch(HookEvent.SESSION_START,
                    HookInput.of(HookEvent.SESSION_START, sessionId,
                            Map.of("userId", request.userId() != null ? request.userId() : ""),
                            Map.of()));
        }

        // CR-030 PRD-194: UserPromptSubmit 훅 (프롬프트 수정 가능)
        HookOutput promptHook = hookDispatcher.dispatch(HookEvent.USER_PROMPT_SUBMIT,
                HookInput.of(HookEvent.USER_PROMPT_SUBMIT, sessionId,
                        Map.of("prompt", extractLastUserMessage(request) != null ? extractLastUserMessage(request) : ""),
                        Map.of("userId", request.userId() != null ? request.userId() : "")));
        if (promptHook.decision() == HookDecision.BLOCK) {
            log.info("User prompt blocked by hook: sessionId={}", sessionId);
            return new ChatResponse(
                    "hook-blocked", "none", sessionId,
                    List.of(new ContentBlock.Text("Request blocked by policy hook")),
                    List.of(), new TokenUsage(0, 0), 0.0, null);
        }

        // CR-034 PRD-231: INSTRUCTIONS_LOADED 훅 (시스템 프롬프트 로드 완료)
        if (request.sessionId() == null) {
            try {
                hookDispatcher.dispatch(HookEvent.INSTRUCTIONS_LOADED,
                        HookInput.of(HookEvent.INSTRUCTIONS_LOADED, sessionId,
                                Map.of("sessionId", sessionId),
                                Map.of()));
            } catch (Exception ignored) {}
        }

        // CR-029: ContextAssemblyEngine으로 컨텍스트 조립 위임
        // B3: circuit breaker — 연속 압축 실패 시 skip
        if (contextAssemblyEngine.shouldSkipCompact(sessionId)) {
            log.warn("Session {} compact skipped (circuit breaker)", sessionId);
        }
        AssemblyResult assemblyResult = contextAssemblyEngine.assemble(sessionId, null, request);
        List<UnifiedMessage> trimmedMessages = new ArrayList<>(assemblyResult.messages());
        // B5: effective window 로깅
        log.debug("Context assembled: recipe={}, tokens={}/{}, layers={}",
                assemblyResult.trace().recipeId(),
                assemblyResult.trace().totalEstimatedTokens(),
                assemblyResult.trace().effectiveWindow(),
                assemblyResult.trace().includedLayers().size());

        // CR-045: workspacePath — 세션 메타(workspaceRef) > 요청값 > null 우선순위.
        // 첫 요청이면 세션 메타에 저장. 기존 메타와 다른 값 요청 시 IllegalStateException(BIZ-091) → Controller 409.
        String workspacePath = resolveWorkspace(request, sessionId);
        // CR-102: workflowRunId/stepId/subagentRunId/connectionId 전파 — 도구 루프 이벤트의 run 타임라인 연결 + 커넥터 표시
        ToolContext toolContext = new ToolContext(
                tenantId, null, null, sessionId,
                request.workflowRunId(), request.workflowStepId(), request.subagentRunId(),
                request.connectionId(),
                request.userId(), PermissionLevel.FULL,
                ApprovalState.NOT_REQUIRED, workspacePath, false, 0);

        // 3-1. RAG 컨텍스트 주입 (Phase 3: ragSourceId 지정 시)
        // CR-058: buildContextWithCitations 사용 — 위젯 UI에 표시할 citations 메타데이터 동반.
        List<Map<String, Object>> ragCitations = null;
        boolean ragUsed = false;
        if (request.ragSourceId() != null && !request.ragSourceId().isBlank()) {
            String userQuery = request.messages().stream()
                    .filter(m -> m.role() == UnifiedMessage.Role.USER)
                    .reduce((first, second) -> second)
                    .map(m -> m.content().stream()
                            .filter(b -> b instanceof ContentBlock.Text)
                            .map(b -> ((ContentBlock.Text) b).text())
                            .reduce("", String::concat))
                    .orElse("");
            if (!userQuery.isBlank()) {
                Map<String, Object> ragResult = ragService.buildContextWithCitations(
                        userQuery, request.ragSourceId(), 5);
                String ragContext = (String) ragResult.getOrDefault("context", "");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> citations =
                        (List<Map<String, Object>>) ragResult.getOrDefault("citations", List.of());
                if (!ragContext.isBlank()) {
                    trimmedMessages.add(0,
                            UnifiedMessage.ofText(UnifiedMessage.Role.SYSTEM, ragContext));
                    ragUsed = true;
                    if (!citations.isEmpty()) {
                        ragCitations = citations;
                    }
                }
            }
        }

        // 4. LLM 어댑터 선택 (connectionGroupId > connectionId > modelRouter)
        //    CR-015: connectionGroupId 지정 시 그룹 전략에 따라 커넥션 선택 + 자동 폴백
        //    CR-029: runtimeRegistry 로깅 (향후 selectBest 연결 지점)
        log.debug("Available runtimes: {}", runtimeRegistry.listCapabilities().size());
        LLMAdapter adapter = null;
        String resolvedModel = null;
        boolean useConnectionGroup = request.connectionGroupId() != null && !request.connectionGroupId().isBlank();
        if (!useConnectionGroup && request.connectionId() != null && !request.connectionId().isBlank()) {
            adapter = connectionAdapterFactory.getAdapter(request.connectionId());
            resolvedModel = connectionAdapterFactory.resolveModel(request.connectionId(), request.model());
        } else if (!useConnectionGroup) {
            // CR-075: connection_id 미지정 + 테넌트 CLI 활성 + anthropic-cli connection 존재 → 자동 선택.
            // 소비앱이 인프라 식별자(connection_id, agent_id) 모두 모르고도 CLI 라우팅되도록.
            String defaultCliConn = connectionAdapterFactory.findDefaultCliConnectionForCurrentTenant();
            if (defaultCliConn != null) {
                adapter = connectionAdapterFactory.getAdapter(defaultCliConn);
                resolvedModel = connectionAdapterFactory.resolveModel(defaultCliConn, request.model());
            } else {
                adapter = modelRouter.route(new LLMRequest(request.model(), trimmedMessages));
                resolvedModel = modelRouter.resolveModelId(request.model());
            }
        } else {
            // connectionGroupId 사용 시: resolvedModel은 그룹 내부에서 결정되므로 요청 모델 기준
            resolvedModel = request.model() != null ? request.model() : "auto";
        }

        // 4-1. CR-007: 구조화 출력 — 스키마 해석
        Map<String, Object> resolvedSchema = resolveResponseSchema(request.responseFormat());

        // 4-2. PRD-128: 응답 캐시 조회 (세션 없는 단발 요청만 캐시 적용)
        String lastUserMsg = extractLastUserMessage(request);
        if (request.sessionId() == null && lastUserMsg != null && !lastUserMsg.isBlank()) {
            try {
                var cached = responseCacheService.get(resolvedModel, null, lastUserMsg);
                if (cached.isPresent()) {
                    log.info("캐시 히트: model={}", resolvedModel);
                    // 세션 저장은 스킵, 캐시 응답 직접 반환
                    return new ChatResponse(
                            "cache-hit", resolvedModel, sessionId,
                            List.of(new ContentBlock.Text(cached.get())),
                            List.of(), new TokenUsage(0, 0), 0.0, null);
                }
            } catch (Exception e) {
                log.debug("캐시 조회 실패 (무시): {}", e.getMessage());
            }
        }

        // 5. LLM 호출 (Phase 2: actionsEnabled=true 시 tool use 루프)
        // CR-030: connection config에서 ModelConfig 구성 (extended_thinking 등)
        ModelConfig modelConfig = (request.connectionId() != null && !request.connectionId().isBlank())
                ? connectionAdapterFactory.resolveModelConfig(request.connectionId())
                : ModelConfig.defaults();

        LLMResponse llmResponse = null;
        long llmStart = System.currentTimeMillis();
        boolean llmSuccess = false;
        // CR-068: 도구 호출 누적 (ChatResponse.actions_executed 채움)
        List<Map<String, Object>> actionsExecuted = List.of();
        try {
            if (request.actionsEnabled() && toolRegistry.hasTools()) {
                // Tool use 루프 — 최대 5회 반복 (CR-006 + CR-029: ToolContext + lineage)
                com.platform.tool.ToolCallHandler.beginActionTracking();
                try {
                    // CR-088: 도구 루프에 resolvedSchema 전달 (AGENT_CALL response_schema 지원)
                    llmResponse = toolCallHandler.executeLoop(
                            adapter, resolvedModel, trimmedMessages,
                            modelConfig, sessionId, toolRegistry,
                            request.toolFilter(), request.toolChoice(), toolContext,
                            resolvedSchema);
                } finally {
                    actionsExecuted = com.platform.tool.ToolCallHandler.drainActionTracking();
                }
            } else {
                LLMRequest llmRequest = new LLMRequest(
                        resolvedModel, trimmedMessages, null,
                        modelConfig, false, sessionId, null, resolvedSchema);
                try {
                    if (useConnectionGroup) {
                        // CR-015: 커넥션 그룹 기반 호출 — 그룹 전략 + 커넥션 레벨 폴백
                        llmResponse = connectionGroupSelector.executeWithGroup(
                                request.connectionGroupId(), llmRequest);
                    } else if (request.connectionId() == null || request.connectionId().isBlank()) {
                        // CR-082: CLI adapter 는 fallback chain 우회 — 다른 provider 로 떨어지면
                        // 소비앱이 401/403 같은 정체불명 에러를 받게 되어 진단 불가.
                        // 실패 사유는 ClaudeCliAdapter 가 명확히 전달한다 (AGENT_OFFLINE / RUNNER_UNREACHABLE 등).
                        if (com.platform.llm.adapter.ClaudeCliAdapter.PROVIDER.equals(adapter.getProvider())) {
                            llmResponse = adapter.chat(llmRequest).get();
                        } else {
                            // PRD-122: Fallback Chain — 모델 레벨 폴백 (비-CLI provider 만)
                            llmResponse = fallbackChainExecutor.execute(
                                    llmRequest, adapter, resolvedModel, modelRouter.getFallbackChain());
                        }
                    } else {
                        // 단일 connectionId 직접 지정 — 폴백 없음
                        llmResponse = adapter.chat(llmRequest).get();
                    }
                } catch (Exception e) {
                    // CR-082: CLI adapter 실패는 RuntimeException 으로 일관 (ResponseStatusException SSE ASYNC dispatch 회피).
                    // 메시지 prefix(`cli_agent_offline:` / `cli_runner_unreachable:`) 로 진단 가능성 유지.
                    log.error("LLM call failed", e);
                    throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
                }
            }
            llmSuccess = true;
        } finally {
            long latencyMs = System.currentTimeMillis() - llmStart;
            String provider = resolvedModel.contains("/") ? resolvedModel.split("/")[0] : "unknown";
            long inputTokens = (llmResponse != null && llmResponse.usage() != null) ? llmResponse.usage().inputTokens() : 0;
            long outputTokens = (llmResponse != null && llmResponse.usage() != null) ? llmResponse.usage().outputTokens() : 0;
            platformMetrics.recordLlmCall(provider, resolvedModel, llmSuccess, latencyMs, inputTokens, outputTokens);

            // PRD-112: LLM 트레이싱 — 비동기 기록 (실패해도 메인 플로우에 영향 없음)
            try {
                if (llmResponse != null) {
                    Map<String, Object> responseMap = Map.of(
                            "text", llmResponse.textContent(),
                            "finish_reason", llmResponse.finishReason().name(),
                            "cost_usd", llmResponse.costUsd()
                    );
                    traceService.record(sessionId, resolvedModel,
                            trimmedMessages.stream().map(m -> Map.of(
                                    "role", m.role().name(),
                                    "content", m.content().stream()
                                            .filter(b -> b instanceof ContentBlock.Text)
                                            .map(b -> ((ContentBlock.Text) b).text())
                                            .reduce("", String::concat)
                            )).toList(),
                            responseMap, (int) inputTokens, (int) outputTokens,
                            (int) latencyMs, llmResponse.costUsd());
                }
            } catch (Exception e) {
                log.warn("Tracing record failed: {}", e.getMessage());
            }

            // B4: 실측 토큰으로 보정 비율 갱신 (다음 assemble에서 사용)
            // total = input + cacheCreation + cacheRead (캐시 포함해야 추정 비교가 정확)
            if (llmResponse != null && llmResponse.usage() != null && inputTokens > 0) {
                int totalActual = (int) inputTokens
                        + llmResponse.usage().cacheCreationInputTokens()
                        + llmResponse.usage().cacheReadInputTokens();
                int estimated = assemblyResult.trace().totalEstimatedTokens();
                contextAssemblyEngine.updateCalibration(sessionId, totalActual, estimated);
            }
        }

        // 5-1. CR-007: 구조화 출력 — 응답 검증 + 재시도 + ContentBlock 변환
        if (resolvedSchema != null) {
            llmResponse = validateAndConvertStructuredOutput(
                    llmResponse, resolvedSchema, adapter, resolvedModel, trimmedMessages, sessionId);
        }

        // 6. 출력 가드레일 검증 (PY-010: MCPSafetyClient validate_output)
        Map<String, Object> guardrailResult = validateOutput(llmResponse.textContent());

        // 7. 세션 저장 (사용자 메시지 + 어시스턴트 최종 응답)
        request.messages().forEach(m -> sessionStore.appendMessage(sessionId, m));
        sessionStore.appendMessage(sessionId,
                UnifiedMessage.ofText(UnifiedMessage.Role.ASSISTANT, llmResponse.textContent()));

        // 7.5 PRD-128: 응답 캐시 저장 (세션 없는 단발 요청만, 유효한 응답만)
        if (request.sessionId() == null && lastUserMsg != null && !lastUserMsg.isBlank()
                && llmSuccess && llmResponse.textContent() != null && !llmResponse.textContent().isBlank()) {
            try {
                int totalTokens = llmResponse.usage() != null
                        ? llmResponse.usage().inputTokens() + llmResponse.usage().outputTokens() : 0;
                responseCacheService.put(resolvedModel, null, lastUserMsg,
                        llmResponse.textContent(), totalTokens);
            } catch (Exception e) {
                log.debug("캐시 저장 실패 (무시): {}", e.getMessage());
            }
        }

        // 8. 사용량 로그 저장
        saveUsageLog(request.userId(), sessionId, resolvedModel, llmResponse);

        // 9. 감사 로그
        auditLogger.logLLMCall(resolvedModel, sessionId,
                llmResponse.usage().inputTokens(), llmResponse.usage().outputTokens());

        // CR-030 PRD-194: SessionEnd 훅
        hookDispatcher.dispatch(HookEvent.SESSION_END,
                HookInput.of(HookEvent.SESSION_END, sessionId,
                        Map.of("model", resolvedModel,
                                "inputTokens", llmResponse.usage().inputTokens(),
                                "outputTokens", llmResponse.usage().outputTokens()),
                        Map.of()));

        // CR-031 PRD-213: 메모리 자동 추출 (fire-and-forget 비동기)
        try {
            List<UnifiedMessage> sessionMessages = sessionStore.getMessages(sessionId);
            memoryAutoExtractService.extractIfEligible(sessionId, request.userId(), sessionMessages);
        } catch (Exception e) {
            log.debug("메모리 자동 추출 트리거 실패 (무시): {}", e.getMessage());
        }

        return new ChatResponse(
                llmResponse.id(),
                resolvedModel,
                sessionId,
                llmResponse.content(),
                actionsExecuted,  // CR-068: ToolCallHandler ThreadLocal 누적
                llmResponse.usage(),
                llmResponse.costUsd(),
                guardrailResult,
                ragCitations,
                ragUsed ? Boolean.TRUE : null  // ragUsed=false 일 때는 JSON에서 생략
        );
    }

    /**
     * 스트리밍 채팅 요청 처리.
     * CR-045 Phase 2-B: StreamEvent 5종(TextDelta/ThinkingDelta/ToolUseStart/ToolResultEvent/Done)
     * 을 streamSink로 흘린다. 도구 루프는 ToolCallHandler.executeLoopStream()에 위임.
     */
    public void chatStream(ChatRequest request,
                            Consumer<com.platform.orchestrator.stream.StreamEvent> streamSink) {
        String sessionId = request.sessionId() != null
                ? request.sessionId()
                : UUID.randomUUID().toString();

        // CR-047 PRD-299: Memory prefetch — assemble() 합류 전까지 백그라운드 진행
        contextAssemblyEngine.prefetchMemory(sessionId, request.userId());

        // CR-046 Phase 2: 동일 sessionId 활성 스트림이 있으면 자동 abort 후 새 요청 처리(옵션 B).
        // register 시 이전 토큰을 cancel 처리하여 끼어들기 자동 동작.
        AtomicBoolean cancelled = cancellationRegistry.register(sessionId);

        // CR-013: LLM 토큰 쿼터 체크
        String streamTenantId = TenantContext.getTenantId();
        if (streamTenantId != null) {
            quotaService.checkLLMQuota(streamTenantId, 1000).throwIfExceeded();
        }

        List<UnifiedMessage> history = sessionStore.getMessages(sessionId);
        List<UnifiedMessage> allMessages = new ArrayList<>(history);
        allMessages.addAll(request.messages());
        List<UnifiedMessage> trimmedMessages = contextWindowManager.trim(allMessages);

        // CR-058: RAG 주입 — 비스트림 경로와 동일 정책. citations 는 Done 이벤트에 실어 전달.
        final List<Map<String, Object>> streamRagCitations;
        final boolean streamRagUsed;
        if (request.ragSourceId() != null && !request.ragSourceId().isBlank()) {
            String userQuery = request.messages().stream()
                    .filter(m -> m.role() == UnifiedMessage.Role.USER)
                    .reduce((first, second) -> second)
                    .map(m -> m.content().stream()
                            .filter(b -> b instanceof ContentBlock.Text)
                            .map(b -> ((ContentBlock.Text) b).text())
                            .reduce("", String::concat))
                    .orElse("");
            if (!userQuery.isBlank()) {
                Map<String, Object> ragResult = ragService.buildContextWithCitations(
                        userQuery, request.ragSourceId(), 5);
                String ragContext = (String) ragResult.getOrDefault("context", "");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> citations =
                        (List<Map<String, Object>>) ragResult.getOrDefault("citations", List.of());
                if (!ragContext.isBlank()) {
                    trimmedMessages = new ArrayList<>(trimmedMessages);
                    trimmedMessages.add(0,
                            UnifiedMessage.ofText(UnifiedMessage.Role.SYSTEM, ragContext));
                    streamRagUsed = true;
                    streamRagCitations = citations.isEmpty() ? null : citations;
                } else {
                    streamRagUsed = false;
                    streamRagCitations = null;
                }
            } else {
                streamRagUsed = false;
                streamRagCitations = null;
            }
        } else {
            streamRagUsed = false;
            streamRagCitations = null;
        }

        // CR-058: Done 이벤트에 citations 를 합쳐 내보내는 decorator.
        Consumer<com.platform.orchestrator.stream.StreamEvent> streamSinkWithCitations = ev -> {
            if (ev instanceof com.platform.orchestrator.stream.StreamEvent.Done d) {
                streamSink.accept(new com.platform.orchestrator.stream.StreamEvent.Done(
                        d.usage(), streamRagCitations, streamRagUsed ? Boolean.TRUE : null));
            } else {
                streamSink.accept(ev);
            }
        };

        // CR-045: workspacePath 결정 (비스트리밍 경로와 동일)
        String tenantId = TenantContext.getTenantId();
        String workspacePath = resolveWorkspace(request, sessionId);
        // CR-102: workflowRunId/stepId/subagentRunId/connectionId 전파 (스트리밍 경로 동일)
        ToolContext toolContext = new ToolContext(
                tenantId, null, null, sessionId,
                request.workflowRunId(), request.workflowStepId(), request.subagentRunId(),
                request.connectionId(),
                request.userId(), PermissionLevel.FULL,
                ApprovalState.NOT_REQUIRED, workspacePath, false, 0);

        LLMAdapter adapter;
        String resolvedModel;
        if (request.connectionGroupId() != null && !request.connectionGroupId().isBlank()) {
            var selectedConn = connectionGroupSelector.selectConnection(request.connectionGroupId());
            adapter = connectionAdapterFactory.getAdapter(selectedConn);
            resolvedModel = connectionAdapterFactory.resolveModel(selectedConn, request.model());
        } else if (request.connectionId() != null && !request.connectionId().isBlank()) {
            adapter = connectionAdapterFactory.getAdapter(request.connectionId());
            resolvedModel = connectionAdapterFactory.resolveModel(request.connectionId(), request.model());
        } else {
            // CR-075: connection_id 미지정 시 테넌트 기본 anthropic-cli 자동 선택 (스트리밍 경로 동일)
            String defaultCliConn = connectionAdapterFactory.findDefaultCliConnectionForCurrentTenant();
            if (defaultCliConn != null) {
                adapter = connectionAdapterFactory.getAdapter(defaultCliConn);
                resolvedModel = connectionAdapterFactory.resolveModel(defaultCliConn, request.model());
            } else {
                adapter = modelRouter.route(new LLMRequest(request.model(), trimmedMessages));
                resolvedModel = modelRouter.resolveModelId(request.model());
            }
        }

        ModelConfig streamModelConfig = (request.connectionId() != null && !request.connectionId().isBlank())
                ? connectionAdapterFactory.resolveModelConfig(request.connectionId())
                : ModelConfig.defaults();

        // CR-045 Phase 2-B: 도구 루프 통합 스트리밍
        LLMResponse finalResponse;
        try {
            if (request.actionsEnabled() && toolRegistry.hasTools()) {
                finalResponse = toolCallHandler.executeLoopStream(
                        adapter, resolvedModel, trimmedMessages,
                        streamModelConfig, sessionId, toolRegistry,
                        request.toolFilter(), request.toolChoice(), toolContext,
                        streamSinkWithCitations, cancelled);
            } else {
                // 도구 비활성/없음 → 단순 스트리밍
                LLMRequest llmRequest = new LLMRequest(
                        resolvedModel, trimmedMessages, null,
                        streamModelConfig, true, sessionId);
                StringBuilder textBuf = new StringBuilder();
                final TokenUsage[] usageHolder = new TokenUsage[]{null};
                final String[] idHolder = new String[]{""};
                // CR-045: adapter.chatStream은 fire-and-forget(가상 스레드)이므로 CountDownLatch로 완료 대기.
                java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                adapter.chatStream(llmRequest, chunk -> {
                    // CR-046: 중지 요청 시 즉시 람다 종료 (이후 chunk는 무시)
                    if (cancelled.get()) {
                        if (latch.getCount() > 0) latch.countDown();
                        return;
                    }
                    if (idHolder[0].isEmpty() && chunk.id() != null) idHolder[0] = chunk.id();
                    if (chunk.done()) {
                        usageHolder[0] = chunk.usage();
                        latch.countDown();
                        return;
                    }
                    if (chunk.delta() == null) return;
                    if ("thinking".equals(chunk.type())) {
                        streamSinkWithCitations.accept(new com.platform.orchestrator.stream.StreamEvent.ThinkingDelta(chunk.delta()));
                    } else {
                        textBuf.append(chunk.delta());
                        streamSinkWithCitations.accept(new com.platform.orchestrator.stream.StreamEvent.TextDelta(chunk.delta()));
                    }
                });
                try {
                    latch.await(5, java.util.concurrent.TimeUnit.MINUTES);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                String finalText = textBuf.toString();
                if (cancelled.get()) {
                    finalText = "[중단됨] " + finalText;
                }
                finalResponse = new LLMResponse(idHolder[0], resolvedModel,
                        List.of(new ContentBlock.Text(finalText)),
                        List.of(), usageHolder[0] != null ? usageHolder[0] : new TokenUsage(0, 0),
                        LLMResponse.FinishReason.END, 0, 0);
                streamSinkWithCitations.accept(new com.platform.orchestrator.stream.StreamEvent.Done(usageHolder[0]));
            }

            // 세션 저장 + 사용량 로그
            boolean wasCancelled = cancelled.get();
            request.messages().forEach(m -> sessionStore.appendMessage(sessionId, m));
            String assistantText = finalResponse.textContent() != null ? finalResponse.textContent() : "";
            // 도구 루프 경로의 partial은 Handler가 텍스트만 반환 → 여기서 prefix 부착
            if (wasCancelled && !assistantText.startsWith("[중단됨]")) {
                assistantText = "[중단됨] " + assistantText;
            }
            sessionStore.appendMessage(sessionId,
                    UnifiedMessage.ofText(UnifiedMessage.Role.ASSISTANT, assistantText));
            if (finalResponse.usage() != null) {
                saveUsageLog(request.userId(), sessionId, resolvedModel, finalResponse);
            }
        } finally {
            cancellationRegistry.clear(sessionId);
        }
    }

    /**
     * 출력 가드레일 검증 (PY-010).
     * MCPSafetyClient의 validate_output 도구를 호출하여 LLM 응답의 안전성을 검증.
     * MCP 미연결 시 null 반환 (가드레일 미적용).
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> validateOutput(String outputText) {
        if (!mcpSafetyClient.isAvailable() || outputText == null || outputText.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> result = mcpSafetyClient.validateOutput(outputText);
            boolean safe = Boolean.TRUE.equals(result.get("safe"));
            if (!safe) {
                int violationCount = result.get("violation_count") instanceof Number n ? n.intValue() : 0;
                log.warn("Output guardrail violations detected: count={}", violationCount);
            }
            return result;
        } catch (Exception e) {
            log.warn("Output guardrail validation failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * CR-007: response_format에서 JSON Schema를 해석.
     * schema_ref → SchemaRegistry 조회, schema → 인라인 사용. 둘 다 없으면 null.
     */
    private Map<String, Object> resolveResponseSchema(ChatRequest.ResponseFormat format) {
        if (format == null || !"json_schema".equals(format.type())) {
            return null;
        }
        if (format.schemaRef() != null && !format.schemaRef().isBlank()) {
            // schema_ref: "schemaId" 또는 "schemaId/version"
            String ref = format.schemaRef();
            if (ref.contains("/")) {
                String[] parts = ref.split("/", 2);
                try {
                    int version = Integer.parseInt(parts[1]);
                    return schemaRegistry.getSchema(parts[0], version)
                            .orElseThrow(() -> new IllegalArgumentException("Schema not found: " + ref));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid schema_ref version: " + ref);
                }
            }
            return schemaRegistry.getLatestSchema(ref)
                    .orElseThrow(() -> new IllegalArgumentException("Schema not found: " + ref));
        }
        if (format.schema() != null && !format.schema().isEmpty()) {
            return format.schema();
        }
        return null;
    }

    /**
     * CR-007: LLM 응답의 JSON을 파싱 → 스키마 검증 → 실패 시 최대 2회 재시도 → Structured ContentBlock 변환.
     */
    @SuppressWarnings("unchecked")
    private LLMResponse validateAndConvertStructuredOutput(
            LLMResponse original, Map<String, Object> schema,
            LLMAdapter adapter, String resolvedModel,
            List<UnifiedMessage> messages, String sessionId) {

        LLMResponse current = original;
        for (int attempt = 0; attempt <= STRUCTURED_OUTPUT_MAX_RETRIES; attempt++) {
            String jsonText = current.textContent();
            // JSON 블록 추출 (```json ... ``` 또는 순수 JSON)
            String cleanJson = extractJsonFromText(jsonText);

            try {
                Map<String, Object> data = objectMapper.readValue(cleanJson, Map.class);
                SchemaValidator.ValidationResult result = schemaValidator.validate(schema, data);

                if (result.valid()) {
                    // 성공: Structured 블록으로 대체
                    String schemaName = schema.getOrDefault("title", "inline").toString();
                    List<ContentBlock> newContent = List.of(new ContentBlock.Structured(schemaName, data));
                    return new LLMResponse(current.id(), current.model(), newContent,
                            current.toolCalls(), current.usage(), current.finishReason(),
                            current.latencyMs(), current.costUsd());
                }

                if (attempt < STRUCTURED_OUTPUT_MAX_RETRIES) {
                    log.warn("Structured output validation failed (attempt {}): {}", attempt + 1, result.errors());
                    // 재시도: 검증 오류를 포함한 메시지로 재호출
                    List<UnifiedMessage> retryMessages = new ArrayList<>(messages);
                    retryMessages.add(UnifiedMessage.ofText(UnifiedMessage.Role.ASSISTANT, jsonText));
                    retryMessages.add(UnifiedMessage.ofText(UnifiedMessage.Role.USER,
                            "The JSON output did not pass schema validation. Errors: " + result.errors()
                                    + "\nPlease fix and return valid JSON only."));

                    LLMRequest retryRequest = new LLMRequest(
                            resolvedModel, retryMessages, null,
                            ModelConfig.defaults(), false, sessionId, null, schema);
                    try {
                        current = adapter.chat(retryRequest).get();
                    } catch (Exception e) {
                        log.error("Structured output retry failed", e);
                        break;
                    }
                } else {
                    log.error("Structured output validation failed after {} retries: {}",
                            STRUCTURED_OUTPUT_MAX_RETRIES, result.errors());
                }
            } catch (Exception e) {
                if (attempt < STRUCTURED_OUTPUT_MAX_RETRIES) {
                    log.warn("Structured output JSON parse failed (attempt {}): {}", attempt + 1, e.getMessage());
                    List<UnifiedMessage> retryMessages = new ArrayList<>(messages);
                    retryMessages.add(UnifiedMessage.ofText(UnifiedMessage.Role.ASSISTANT, jsonText));
                    retryMessages.add(UnifiedMessage.ofText(UnifiedMessage.Role.USER,
                            "Your response was not valid JSON. Error: " + e.getMessage()
                                    + "\nPlease return valid JSON only, matching the requested schema."));

                    LLMRequest retryRequest = new LLMRequest(
                            resolvedModel, retryMessages, null,
                            ModelConfig.defaults(), false, sessionId, null, schema);
                    try {
                        current = adapter.chat(retryRequest).get();
                    } catch (Exception retryEx) {
                        log.error("Structured output retry failed", retryEx);
                        break;
                    }
                } else {
                    log.error("Structured output parse failed after {} retries", STRUCTURED_OUTPUT_MAX_RETRIES);
                }
            }
        }
        // 모든 재시도 실패: 원본 텍스트 그대로 반환
        return current;
    }

    /** JSON 텍스트에서 ```json ... ``` 코드 블록 또는 순수 JSON 추출 */
    private String extractJsonFromText(String text) {
        if (text == null) return "{}";
        String trimmed = text.strip();
        // ```json ... ``` 패턴
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf('\n');
            int end = trimmed.lastIndexOf("```");
            if (start > 0 && end > start) {
                return trimmed.substring(start + 1, end).strip();
            }
        }
        return trimmed;
    }

    /** 마지막 사용자 메시지 텍스트 추출 (캐시 키용) */
    private String extractLastUserMessage(ChatRequest request) {
        if (request.messages() == null) return null;
        return request.messages().stream()
                .filter(m -> m.role() == UnifiedMessage.Role.USER)
                .reduce((a, b) -> b)
                .map(m -> m.content().stream()
                        .filter(b -> b instanceof ContentBlock.Text)
                        .map(b -> ((ContentBlock.Text) b).text())
                        .reduce("", String::concat))
                .orElse(null);
    }

    private void saveUsageLog(String userId, String sessionId, String model, LLMResponse response) {
        try {
            UsageLogEntity usage = new UsageLogEntity();
            usage.setUserId(userId);
            usage.setSessionId(sessionId);
            usage.setModel(model);
            usage.setInputTokens(response.usage().inputTokens());
            usage.setOutputTokens(response.usage().outputTokens());
            usage.setCostUsd(BigDecimal.valueOf(response.costUsd()));
            usageLogRepository.save(usage);
        } catch (Exception e) {
            log.warn("Failed to save usage log: {}", e.getMessage());
        }
    }

    /**
     * CR-045: workspacePath 결정 — 세션 메타(workspaceRef) > 요청값 > null 우선순위.
     * - 세션 메타에 값이 있으면 그것 사용 (세션 도중 변경 금지, BIZ-091)
     * - 요청값이 있고 세션 메타가 비어있으면 메타에 저장 후 사용
     * - 요청값이 세션 메타와 다르면 IllegalStateException → 컨트롤러 단에서 409
     * - 둘 다 없으면 null (ToolContext.workspacePath=null → resolver 폴백)
     */
    private String resolveWorkspace(ChatRequest request, String sessionId) {
        String existing = sessionId != null ? sessionStore.getWorkspaceRef(sessionId) : null;
        String requested = request.workingDirectory();

        if (existing != null && !existing.isBlank()) {
            if (requested != null && !requested.isBlank() && !requested.equals(existing)) {
                throw new IllegalStateException(
                        "세션 workspaceRef 충돌: existing=" + existing + ", requested=" + requested);
            }
            return existing;
        }
        if (requested != null && !requested.isBlank() && sessionId != null) {
            sessionStore.setWorkspaceRefIfAbsent(sessionId, requested);
            return requested;
        }
        return requested;
    }

}
