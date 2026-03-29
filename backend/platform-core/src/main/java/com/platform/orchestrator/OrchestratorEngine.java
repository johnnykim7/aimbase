package com.platform.orchestrator;

import com.platform.action.ActionExecutor;
import com.platform.action.model.*;
import com.platform.domain.UsageLogEntity;
import com.platform.llm.ConnectionAdapterFactory;
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
import com.platform.session.ContextWindowManager;
import com.platform.session.MemoryService;
import com.platform.session.ResponseCacheService;
import com.platform.session.SessionStore;
import com.platform.tool.ToolCallHandler;
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

    private static final int STRUCTURED_OUTPUT_MAX_RETRIES = 2;

    public OrchestratorEngine(
            ModelRouter modelRouter,
            FallbackChainExecutor fallbackChainExecutor,
            ConnectionAdapterFactory connectionAdapterFactory,
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
            MemoryService memoryService
    ) {
        this.modelRouter = modelRouter;
        this.fallbackChainExecutor = fallbackChainExecutor;
        this.connectionAdapterFactory = connectionAdapterFactory;
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
    }

    /**
     * 동기 채팅 요청 처리
     */
    public ChatResponse chat(ChatRequest request) {
        String sessionId = request.sessionId() != null
                ? request.sessionId()
                : UUID.randomUUID().toString();

        // 1. 세션 히스토리 로드
        List<UnifiedMessage> history = sessionStore.getMessages(sessionId);

        // 1.5 PRD-130: 메모리 컨텍스트 주입 (SYSTEM_RULES + LONG_TERM + USER_PROFILE)
        List<UnifiedMessage> memoryContext = memoryService.buildMemoryContext(sessionId, request.userId());

        // 2. 현재 요청 메시지 추가
        List<UnifiedMessage> allMessages = new ArrayList<>(memoryContext);
        allMessages.addAll(history);
        allMessages.addAll(request.messages());

        // 3. 컨텍스트 윈도우 트리밍
        List<UnifiedMessage> trimmedMessages = new ArrayList<>(contextWindowManager.trim(allMessages));

        // 3-1. RAG 컨텍스트 주입 (Phase 3: ragSourceId 지정 시)
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
                String ragContext = ragService.buildContext(userQuery, request.ragSourceId(), 5);
                if (!ragContext.isBlank()) {
                    trimmedMessages.add(0,
                            UnifiedMessage.ofText(UnifiedMessage.Role.SYSTEM, ragContext));
                }
            }
        }

        // 4. LLM 어댑터 선택 (connectionId 우선, 없으면 modelRouter 폴백)
        LLMAdapter adapter;
        String resolvedModel;
        if (request.connectionId() != null && !request.connectionId().isBlank()) {
            adapter = connectionAdapterFactory.getAdapter(request.connectionId());
            resolvedModel = connectionAdapterFactory.resolveModel(request.connectionId(), request.model());
        } else {
            adapter = modelRouter.route(new LLMRequest(request.model(), trimmedMessages));
            resolvedModel = modelRouter.resolveModelId(request.model());
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
        LLMResponse llmResponse = null;
        long llmStart = System.currentTimeMillis();
        boolean llmSuccess = false;
        try {
            if (request.actionsEnabled() && toolRegistry.hasTools()) {
                // Tool use 루프 — 최대 5회 반복 (CR-006: 필터링 + 선택 전략 지원)
                llmResponse = toolCallHandler.executeLoop(
                        adapter, resolvedModel, trimmedMessages,
                        ModelConfig.defaults(), sessionId, toolRegistry,
                        request.toolFilter(), request.toolChoice());
            } else {
                LLMRequest llmRequest = new LLMRequest(
                        resolvedModel, trimmedMessages, null,
                        ModelConfig.defaults(), false, sessionId, null, resolvedSchema);
                try {
                    // PRD-122: Fallback Chain — connectionId 직접 지정이 아닌 경우 fallback 적용
                    if (request.connectionId() == null || request.connectionId().isBlank()) {
                        llmResponse = fallbackChainExecutor.execute(
                                llmRequest, adapter, resolvedModel, modelRouter.getFallbackChain());
                    } else {
                        llmResponse = adapter.chat(llmRequest).get();
                    }
                } catch (Exception e) {
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

        // 7.5 PRD-128: 응답 캐시 저장 (세션 없는 단발 요청만)
        if (request.sessionId() == null && lastUserMsg != null && !lastUserMsg.isBlank()) {
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

        return new ChatResponse(
                llmResponse.id(),
                resolvedModel,
                sessionId,
                llmResponse.content(),
                List.of(),  // actions_executed
                llmResponse.usage(),
                llmResponse.costUsd(),
                guardrailResult
        );
    }

    /**
     * 스트리밍 채팅 요청 처리
     */
    public void chatStream(ChatRequest request, Consumer<LLMStreamChunk> chunkConsumer) {
        String sessionId = request.sessionId() != null
                ? request.sessionId()
                : UUID.randomUUID().toString();

        List<UnifiedMessage> history = sessionStore.getMessages(sessionId);
        List<UnifiedMessage> allMessages = new ArrayList<>(history);
        allMessages.addAll(request.messages());
        List<UnifiedMessage> trimmedMessages = contextWindowManager.trim(allMessages);

        LLMAdapter adapter;
        String resolvedModel;
        if (request.connectionId() != null && !request.connectionId().isBlank()) {
            adapter = connectionAdapterFactory.getAdapter(request.connectionId());
            resolvedModel = connectionAdapterFactory.resolveModel(request.connectionId(), request.model());
        } else {
            adapter = modelRouter.route(new LLMRequest(request.model(), trimmedMessages));
            resolvedModel = modelRouter.resolveModelId(request.model());
        }

        LLMRequest llmRequest = new LLMRequest(
                resolvedModel, trimmedMessages, null,
                ModelConfig.defaults(), true, sessionId);

        StringBuilder fullResponse = new StringBuilder();
        adapter.chatStream(llmRequest, chunk -> {
            if (chunk.delta() != null) {
                fullResponse.append(chunk.delta());
            }
            if (chunk.done()) {
                // 스트리밍 완료 후 세션 저장
                request.messages().forEach(m -> sessionStore.appendMessage(sessionId, m));
                sessionStore.appendMessage(sessionId,
                        UnifiedMessage.ofText(UnifiedMessage.Role.ASSISTANT, fullResponse.toString()));
                if (chunk.usage() != null) {
                    saveUsageLog(request.userId(), sessionId, resolvedModel,
                            new LLMResponse(chunk.id(), resolvedModel, List.of(),
                                    List.of(), chunk.usage(), LLMResponse.FinishReason.END, 0, 0));
                }
            }
            chunkConsumer.accept(chunk);
        });
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
}
