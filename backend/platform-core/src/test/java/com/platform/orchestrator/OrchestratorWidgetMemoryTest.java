package com.platform.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.action.ActionExecutor;
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
import com.platform.session.WidgetPreferenceService;
import com.platform.tenant.quota.QuotaService;
import com.platform.tool.ToolCallHandler;
import com.platform.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
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
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

/**
 * CR-179 TC-CHT-053: 위젯 대화가 정상으로 끝났을 때만 자동 기억 뽑기를 부른다(BIZ-182).
 * TC-CHT-058: 일반, 실시간 두 경로 모두 기억 줄을 붙인 메시지로 모델을 부른다(BIZ-181).
 */
class OrchestratorWidgetMemoryTest {

    private static final String CONN = "cli-runner-bp-wes-cs-work01";

    private final ConnectionAdapterFactory factory = mock(ConnectionAdapterFactory.class);
    private final LLMAdapter adapter = mock(LLMAdapter.class);
    private final HookDispatcher hooks = mock(HookDispatcher.class);
    private final ContextAssemblyEngine contextAssembly = mock(ContextAssemblyEngine.class);
    private final ResponseCacheService responseCache = mock(ResponseCacheService.class);
    private final CancellationRegistry cancellation = mock(CancellationRegistry.class);
    private final ContextWindowManager contextWindow = mock(ContextWindowManager.class);
    private final MemoryAutoExtractService memoryExtract = mock(MemoryAutoExtractService.class);
    private final WidgetPreferenceService prefs = mock(WidgetPreferenceService.class);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private OrchestratorEngine engine;

    @BeforeEach
    void setUp() {
        List<UnifiedMessage> msgs = List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "답은 표로 주세요"));
        when(hooks.dispatch(any(), any())).thenReturn(HookOutput.PASSTHROUGH);
        when(prefs.attach(any(), any())).thenAnswer(inv -> {
            List<UnifiedMessage> out = new ArrayList<>(inv.<List<UnifiedMessage>>getArgument(0));
            out.add(out.size() - 1, UnifiedMessage.turnScopedSystem("MEMORY"));
            return out;
        });
        when(contextAssembly.assemble(anyString(), any(), any())).thenReturn(new AssemblyResult(msgs,
                new AssemblyTrace("r", "test", List.of(), List.of(), List.of(), 10, 1000, 0)));
        when(contextWindow.trim(any())).thenReturn(msgs);
        when(factory.getAdapter(CONN)).thenReturn(adapter);
        when(factory.resolveModel(eq(CONN), any())).thenReturn("claude-cli/sonnet");
        when(factory.resolveModelConfig(CONN)).thenReturn(ModelConfig.defaults());
        when(responseCache.get(any(), any(), any())).thenReturn(Optional.empty());
        when(cancellation.register(anyString())).thenReturn(cancelled);
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
                contextAssembly, mock(RuntimeRegistry.class), hooks, memoryExtract,
                cancellation, mock(ChatRunRecorder.class), turnInstructions,
                new ChatFeatureSupport(mock(com.platform.attachment.AttachmentService.class),
                        mock(com.platform.service.PromptTemplateService.class)),
                mock(com.platform.session.ChatWorkspaceAssigner.class),
                new FileCreateToolGate(mock(com.platform.config.PlatformSettingsService.class)),
                prefs, new com.platform.orchestrator.approval.ChatTurnRegistry());
    }

    private static ChatRequest request() {
        return new ChatRequest(null, "widget-s-1",
                List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "답은 표로 주세요")),
                false, false, null, null, CONN, null, null, null, null, null)
                .withWidgetTurn(new ChatRequest.WidgetTurn("tester-a", null,
                        java.util.Map.of("text", "답은 표로 주세요", "attachments", List.of()), true));
    }

    @Test
    void 일반_응답이_정상으로_끝나면_한_번_부른다() {
        ChatRequest req = request();
        engine.chat(req);
        verify(memoryExtract, times(1)).extractAfterTurn(same(req), eq("widget-s-1"), any());
    }

    @Test
    void 일반_응답이_실패하면_부르지_않는다() {
        when(adapter.chat(any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("runner down")));
        assertThatThrownBy(() -> engine.chat(request())).isInstanceOf(RuntimeException.class);
        verify(memoryExtract, never()).extractAfterTurn(any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void 실시간_응답이_정상으로_끝나면_한_번_부른다() {
        doAnswer(inv -> {
            Consumer<LLMStreamChunk> sink = inv.getArgument(1);
            sink.accept(LLMStreamChunk.text("r1", "claude-cli/sonnet", "답"));
            sink.accept(new LLMStreamChunk("r1", "claude-cli/sonnet", null, true, new TokenUsage(10, 5)));
            return null;
        }).when(adapter).chatStream(any(), any(Consumer.class));

        ChatRequest req = request();
        engine.chatStream(req, ev -> { });

        verify(memoryExtract, times(1)).extractAfterTurn(same(req), eq("widget-s-1"), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void 실시간_응답을_중지하면_부르지_않는다() {
        doAnswer(inv -> {
            Consumer<LLMStreamChunk> sink = inv.getArgument(1);
            sink.accept(LLMStreamChunk.text("r1", "claude-cli/sonnet", "답"));
            cancelled.set(true);
            sink.accept(new LLMStreamChunk("r1", "claude-cli/sonnet", null, true, new TokenUsage(10, 5)));
            return null;
        }).when(adapter).chatStream(any(), any(Consumer.class));

        engine.chatStream(request(), ev -> { });

        verify(memoryExtract, never()).extractAfterTurn(any(), any(), any());
    }

    @Test
    void 일반_응답은_기억_줄을_붙인_메시지로_모델을_부른다() {
        ChatRequest req = request();
        engine.chat(req);

        ArgumentCaptor<LLMRequest> sent = ArgumentCaptor.forClass(LLMRequest.class);
        verify(adapter).chat(sent.capture());
        verify(prefs).attach(any(), same(req));
        assertMemoryBeforeQuestion(sent.getValue().messages());
    }

    @Test
    @SuppressWarnings("unchecked")
    void 실시간_응답도_기억_줄을_붙인_메시지로_모델을_부른다() {
        doAnswer(inv -> {
            Consumer<LLMStreamChunk> sink = inv.getArgument(1);
            sink.accept(new LLMStreamChunk("r1", "claude-cli/sonnet", null, true, new TokenUsage(10, 5)));
            return null;
        }).when(adapter).chatStream(any(), any(Consumer.class));

        ChatRequest req = request();
        engine.chatStream(req, ev -> { });

        ArgumentCaptor<LLMRequest> sent = ArgumentCaptor.forClass(LLMRequest.class);
        verify(adapter).chatStream(sent.capture(), any(Consumer.class));
        verify(prefs).attach(any(), same(req));
        assertMemoryBeforeQuestion(sent.getValue().messages());
    }

    private static void assertMemoryBeforeQuestion(List<UnifiedMessage> messages) {
        int last = messages.size() - 1;
        assertThat(messages.get(last).role()).isEqualTo(UnifiedMessage.Role.USER);
        assertThat(messages.get(last - 1).turnScoped()).isTrue();
        assertThat(((ContentBlock.Text) messages.get(last - 1).content().get(0)).text()).isEqualTo("MEMORY");
    }
}
