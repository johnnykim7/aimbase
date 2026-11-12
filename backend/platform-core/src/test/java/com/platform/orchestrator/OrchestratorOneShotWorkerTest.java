package com.platform.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.action.ActionExecutor;
import com.platform.config.WorkspaceProperties;
import com.platform.context.AssemblyResult;
import com.platform.context.AssemblyTrace;
import com.platform.context.ContextAssemblyEngine;
import com.platform.hook.HookDispatcher;
import com.platform.hook.HookOutput;
import com.platform.llm.ConnectionAdapterFactory;
import com.platform.llm.ConnectionGroupSelector;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.LLMRequest;
import com.platform.llm.model.LLMResponse;
import com.platform.llm.model.LLMStreamChunk;
import com.platform.llm.model.ModelConfig;
import com.platform.llm.model.TokenUsage;
import com.platform.llm.model.UnifiedMessage;
import com.platform.llm.router.FallbackChainExecutor;
import com.platform.llm.router.ModelRouter;
import com.platform.monitoring.PlatformMetrics;
import com.platform.monitoring.TraceService;
import com.platform.policy.AuditLogger;
import com.platform.policy.MCPSafetyClient;
import com.platform.policy.PolicyEngine;
import com.platform.rag.RAGService;
import com.platform.repository.UsageLogRepository;
import com.platform.runtime.RuntimeRegistry;
import com.platform.schema.SchemaRegistry;
import com.platform.schema.SchemaValidator;
import com.platform.session.CancellationRegistry;
import com.platform.session.ContextWindowManager;
import com.platform.session.MemoryAutoExtractService;
import com.platform.session.MemoryService;
import com.platform.session.ResponseCacheService;
import com.platform.session.SessionStore;
import com.platform.tenant.quota.QuotaService;
import com.platform.tool.ToolCallHandler;
import com.platform.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 세션 없이 온 요청은 답한 뒤 CLI 실행기의 프로세스를 닫는다. 닫지 않으면 요청마다 프로세스가 15분씩 남아
 * 운영 CS 실행기가 메모리 한도를 넘었다(CR-173 13절).
 */
class OrchestratorOneShotWorkerTest {

    private static final String CONN = "cli-runner-bp-wes-cs-work01";

    private final ConnectionAdapterFactory factory = mock(ConnectionAdapterFactory.class);
    private final LLMAdapter adapter = mock(LLMAdapter.class);
    private final HookDispatcher hooks = mock(HookDispatcher.class);
    private final ContextAssemblyEngine contextAssembly = mock(ContextAssemblyEngine.class);
    private final ResponseCacheService responseCache = mock(ResponseCacheService.class);
    private final CancellationRegistry cancellation = mock(CancellationRegistry.class);
    private final ContextWindowManager contextWindow = mock(ContextWindowManager.class);
    private OrchestratorEngine engine;

    @BeforeEach
    void setUp() {
        List<UnifiedMessage> msgs = List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "주문 상태 알려줘"));
        when(hooks.dispatch(any(), any())).thenReturn(HookOutput.PASSTHROUGH);
        when(contextAssembly.assemble(anyString(), any(), any())).thenReturn(new AssemblyResult(msgs,
                new AssemblyTrace("r", "test", List.of(), List.of(), List.of(), 10, 1000, 0)));
        when(contextWindow.trim(any())).thenReturn(msgs);
        when(factory.getAdapter(CONN)).thenReturn(adapter);
        when(factory.resolveModel(eq(CONN), any())).thenReturn("claude-cli/sonnet");
        when(factory.resolveModelConfig(CONN)).thenReturn(ModelConfig.defaults());
        when(responseCache.get(any(), any(), any())).thenReturn(Optional.empty());
        when(cancellation.register(anyString())).thenReturn(new AtomicBoolean(false));
        when(adapter.getProvider()).thenReturn("claude-cli");
        when(adapter.chat(any())).thenReturn(CompletableFuture.completedFuture(new LLMResponse(
                "r1", "claude-cli/sonnet", List.of(new ContentBlock.Text("답")), List.of(),
                new TokenUsage(10, 5), LLMResponse.FinishReason.END, 0, 0)));

        TurnInstructions turnInstructions = mock(TurnInstructions.class);
        when(turnInstructions.attach(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        engine = new OrchestratorEngine(
                mock(ModelRouter.class), mock(FallbackChainExecutor.class), factory,
                mock(ConnectionGroupSelector.class), mock(SessionStore.class), contextWindow,
                mock(PolicyEngine.class), mock(ActionExecutor.class), mock(AuditLogger.class),
                mock(UsageLogRepository.class), mock(ToolCallHandler.class), mock(ToolRegistry.class),
                mock(RAGService.class), mock(MCPSafetyClient.class), mock(PlatformMetrics.class),
                mock(TraceService.class), mock(SchemaRegistry.class), mock(SchemaValidator.class),
                new ObjectMapper(), responseCache, mock(MemoryService.class), mock(QuotaService.class),
                contextAssembly, mock(RuntimeRegistry.class), hooks, mock(MemoryAutoExtractService.class),
                cancellation, mock(ChatRunRecorder.class), mock(WorkspaceProperties.class), turnInstructions,
                new ChatFeatureSupport(mock(com.platform.attachment.AttachmentService.class),
                        mock(com.platform.service.PromptTemplateService.class)));
    }

    private static ChatRequest request(String sessionId) {
        return new ChatRequest(null, sessionId,
                List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "주문 상태 알려줘")),
                false, false, null, null, CONN, null, null, null, null, null);
    }

    @Test
    void 세션_없는_요청은_답한_뒤_그_세션의_프로세스를_닫는다() {
        ChatResponse resp = engine.chat(request(null));

        ArgumentCaptor<LLMRequest> sent = ArgumentCaptor.forClass(LLMRequest.class);
        verify(adapter).chat(sent.capture());
        assertThat(sent.getValue().sessionId()).isEqualTo(resp.sessionId());
        verify(adapter).cleanupSession(resp.sessionId());
    }

    @Test
    void 세션이_있는_요청은_프로세스를_남겨_다음_턴에_이어_쓴다() {
        engine.chat(request("s-1"));

        verify(adapter, never()).cleanupSession(anyString());
    }

    @Test
    void 세션_없는_요청이_실패해도_프로세스를_닫는다() {
        when(adapter.chat(any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("runner down")));

        assertThatThrownBy(() -> engine.chat(request(null))).isInstanceOf(RuntimeException.class);

        ArgumentCaptor<LLMRequest> sent = ArgumentCaptor.forClass(LLMRequest.class);
        verify(adapter).chat(sent.capture());
        verify(adapter).cleanupSession(sent.getValue().sessionId());
    }

    @Test
    void 캐시로_답한_요청은_프로세스를_띄우지_않았으니_닫을_것도_없다() {
        when(responseCache.get(any(), any(), any())).thenReturn(Optional.of("캐시 답"));

        engine.chat(request(null));

        verify(adapter, never()).chat(any());
        verify(adapter, never()).cleanupSession(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void 세션_없는_스트림_요청도_답한_뒤_프로세스를_닫는다() {
        doAnswer(inv -> {
            Consumer<LLMStreamChunk> sink = inv.getArgument(1);
            sink.accept(LLMStreamChunk.text("r1", "claude-cli/sonnet", "답"));
            sink.accept(new LLMStreamChunk("r1", "claude-cli/sonnet", null, true, new TokenUsage(10, 5)));
            return null;
        }).when(adapter).chatStream(any(), any(Consumer.class));

        engine.chatStream(request(null), ev -> { });

        ArgumentCaptor<LLMRequest> sent = ArgumentCaptor.forClass(LLMRequest.class);
        verify(adapter).chatStream(sent.capture(), any(Consumer.class));
        verify(adapter).cleanupSession(sent.getValue().sessionId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void 세션이_있는_스트림_요청은_프로세스를_남긴다() {
        doAnswer(inv -> {
            Consumer<LLMStreamChunk> sink = inv.getArgument(1);
            sink.accept(new LLMStreamChunk("r1", "claude-cli/sonnet", null, true, new TokenUsage(10, 5)));
            return null;
        }).when(adapter).chatStream(any(), any(Consumer.class));

        engine.chatStream(request("widget-s-1"), ev -> { });

        verify(adapter, never()).cleanupSession(anyString());
    }
}
