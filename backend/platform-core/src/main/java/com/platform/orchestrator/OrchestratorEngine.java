package com.platform.orchestrator;

import com.platform.action.ActionExecutor;
import com.platform.action.model.*;
import com.platform.domain.UsageLogEntity;
import com.platform.llm.ConnectionAdapterFactory;
import com.platform.llm.LLMAdapterRegistry;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.model.*;
import com.platform.llm.router.ModelRouter;
import com.platform.policy.AuditLogger;
import com.platform.policy.MCPSafetyClient;
import com.platform.policy.PolicyEngine;
import com.platform.policy.model.PolicyResult;
import com.platform.monitoring.PlatformMetrics;
import com.platform.rag.RAGService;
import com.platform.repository.UsageLogRepository;
import com.platform.session.ContextWindowManager;
import com.platform.session.SessionStore;
import com.platform.tool.ToolCallHandler;
import com.platform.tool.ToolRegistry;
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

    public OrchestratorEngine(
            ModelRouter modelRouter,
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
            PlatformMetrics platformMetrics
    ) {
        this.modelRouter = modelRouter;
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

        // 2. 현재 요청 메시지 추가
        List<UnifiedMessage> allMessages = new ArrayList<>(history);
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

        // 5. LLM 호출 (Phase 2: actionsEnabled=true 시 tool use 루프)
        LLMResponse llmResponse = null;
        long llmStart = System.currentTimeMillis();
        boolean llmSuccess = false;
        try {
            if (request.actionsEnabled() && toolRegistry.hasTools()) {
                // Tool use 루프 — 최대 5회 반복
                llmResponse = toolCallHandler.executeLoop(
                        adapter, resolvedModel, trimmedMessages,
                        ModelConfig.defaults(), sessionId, toolRegistry);
            } else {
                LLMRequest llmRequest = new LLMRequest(
                        resolvedModel, trimmedMessages, null,
                        ModelConfig.defaults(), false, sessionId);
                try {
                    llmResponse = adapter.chat(llmRequest).get();
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
        }

        // 6. 출력 가드레일 검증 (PY-010: MCPSafetyClient validate_output)
        Map<String, Object> guardrailResult = validateOutput(llmResponse.textContent());

        // 7. 세션 저장 (사용자 메시지 + 어시스턴트 최종 응답)
        request.messages().forEach(m -> sessionStore.appendMessage(sessionId, m));
        sessionStore.appendMessage(sessionId,
                UnifiedMessage.ofText(UnifiedMessage.Role.ASSISTANT, llmResponse.textContent()));

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
