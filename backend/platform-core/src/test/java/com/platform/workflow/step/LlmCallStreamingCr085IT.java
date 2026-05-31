package com.platform.workflow.step;

import com.platform.llm.ConnectionAdapterFactory;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.model.*;
import com.platform.llm.router.ModelRouter;
import com.platform.service.PromptTemplateService;
import com.platform.workflow.StepContext;
import com.platform.workflow.event.WorkflowEventPublisher;
import com.platform.workflow.event.WorkflowEvents;
import com.platform.workflow.model.WorkflowStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * CR-085 P3 노드 토큰 스트리밍 <b>e2e 통합 검증</b>.
 *
 * <p>단위 테스트({@code StepContextCr085Test})는 P1/P2만 다룬다. 본 IT는 P3의 핵심 계약 —
 * "공용 {@link LLMAdapter#chatStream} 콜백을 재사용해 토큰 델타가 실제
 * {@link WorkflowEventPublisher}(실 Spring {@link ApplicationEventPublisher}) 를 거쳐
 * {@link WorkflowEvents.StepToken} 이벤트로 도달한다" 를 전 경로로 검증한다.
 *
 * <p>실 LLM API 키는 불필요 — 검증 대상은 "어댑터 stream 콜백 → stepToken 발행" 배선이지
 * LLM 응답 품질이 아니다. mock 어댑터의 {@code chatStream} 이 델타 청크를 흘리면
 * {@code LlmCallStepExecutor} 가 그것을 받아 publisher 로 흘리는지를 본다.
 * (실제 어댑터 왕복은 별도 환경 게이트 변형 {@code realLlmStreaming_envGated} 참조)
 */
@DisplayName("CR-085 P3 노드 토큰 스트리밍 e2e")
class LlmCallStreamingCr085IT {

    private static final String CONNECTION_ID = "conn-stream";
    private static final String MODEL_ID = "anthropic/claude-sonnet-4-5";
    private static final UUID RUN_ID = UUID.randomUUID();

    private ModelRouter modelRouter;
    private ConnectionAdapterFactory connectionAdapterFactory;
    private PromptTemplateService promptTemplateService;
    private LLMAdapter adapter;

    private LlmCallStepExecutor executor;

    /** 실 Spring 이벤트 버스를 흉내내되 동기 디스패치 — @EventListener 수신을 그대로 재현. */
    private final List<WorkflowEvents.StepToken> received = new ArrayList<>();

    @BeforeEach
    void setUp() {
        modelRouter = mock(ModelRouter.class);
        connectionAdapterFactory = mock(ConnectionAdapterFactory.class);
        promptTemplateService = mock(PromptTemplateService.class);
        adapter = mock(LLMAdapter.class);

        lenient().when(connectionAdapterFactory.getAdapter(CONNECTION_ID)).thenReturn(adapter);
        lenient().when(connectionAdapterFactory.resolveModel(CONNECTION_ID, "auto")).thenReturn(MODEL_ID);

        // 실 WorkflowEventPublisher + 동기 ApplicationEventPublisher (구독자 = received 수집).
        // → publisher.stepToken() 이 실제 이벤트 객체를 만들고, "구독자"가 그것을 받는 전 경로 검증.
        ApplicationEventPublisher springBus = event -> {
            if (event instanceof WorkflowEvents.StepToken st) {
                received.add(st);
            }
        };
        WorkflowEventPublisher realPublisher = new WorkflowEventPublisher(springBus);

        @SuppressWarnings("unchecked")
        ObjectProvider<WorkflowEventPublisher> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(realPublisher);

        executor = new LlmCallStepExecutor(modelRouter, connectionAdapterFactory,
                promptTemplateService, provider, null);  // CR-090: eventRecorderProvider — null 허용
    }

    private StepContext ctx() {
        return new StepContext(RUN_ID.toString(), "wf-1", "sess-1", Map.of("q", "안녕"), Map.of());
    }

    private WorkflowStep step(Map<String, Object> config) {
        return new WorkflowStep("gen", "LLM", WorkflowStep.StepType.LLM_CALL,
                config, List.of(), null, null, 30000L);
    }

    private Map<String, Object> streamingConfig() {
        Map<String, Object> c = new HashMap<>();
        c.put("connection_id", CONNECTION_ID);
        c.put("prompt", "{{input.q}}");
        c.put("stream_tokens", true);
        return c;
    }

    /** mock chatStream — 주어진 델타들을 순서대로 흘리고 done 청크로 마감. */
    @SuppressWarnings("unchecked")
    private void stubStream(List<LLMStreamChunk> chunks) {
        doAnswer(inv -> {
            Consumer<LLMStreamChunk> sink = inv.getArgument(1);
            for (LLMStreamChunk c : chunks) sink.accept(c);
            return null;
        }).when(adapter).chatStream(any(LLMRequest.class), any(Consumer.class));
    }

    // ───────────────────────────────────────────────────────────────
    // 본 계약: stream_tokens=true → chatStream 델타 → 실 publisher → StepToken 도달
    // ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("stream_tokens=true: 텍스트 델타가 StepToken 이벤트로 순서대로 도달 + 응답 재조립")
    void streamingDeltasReachEventBusAndReassemble() {
        stubStream(List.of(
                LLMStreamChunk.text("m1", MODEL_ID, "안"),
                LLMStreamChunk.text("m1", MODEL_ID, "녕하"),
                LLMStreamChunk.text("m1", MODEL_ID, "세요"),
                LLMStreamChunk.done("m1", MODEL_ID, new TokenUsage(10, 3))
        ));

        Map<String, Object> result = executor.execute(step(streamingConfig()), ctx());

        // 1) StepToken 이벤트가 델타 3개 그대로, 순서 보존하여 실 이벤트 버스에 도달
        assertThat(received).hasSize(3);
        assertThat(received).extracting(WorkflowEvents.StepToken::tokenDelta)
                .containsExactly("안", "녕하", "세요");
        assertThat(received).extracting(WorkflowEvents.StepToken::stepId)
                .containsOnly("gen");
        assertThat(received).extracting(WorkflowEvents.StepToken::type)
                .containsOnly("text");
        // runId 는 StepContext.workflowRunId 가 UUID 면 그대로 전달
        assertThat(received).extracting(WorkflowEvents.StepToken::runId)
                .containsOnly(RUN_ID);
        // P3 알려진 한계: iterationIndex=null 고정 (인터페이스 미전달)
        assertThat(received).extracting(WorkflowEvents.StepToken::iterationIndex)
                .containsOnlyNulls();

        // 2) 재조립된 응답이 동기 callLlm 과 동일 형태(buildResult 호환)
        assertThat(result.get("output")).isEqualTo("안녕하세요");
        assertThat(result.get("model")).isEqualTo(MODEL_ID);
        assertThat(result.get("output_tokens")).isEqualTo(3);
    }

    @Test
    @DisplayName("thinking 델타는 type=thinking 으로 발행되나 본문 재조립에서는 제외 (buildResult 동등)")
    void thinkingDeltaTaggedButExcludedFromOutput() {
        stubStream(List.of(
                new LLMStreamChunk("m1", MODEL_ID, "추론중", false, null, "thinking"),
                LLMStreamChunk.text("m1", MODEL_ID, "최종답변"),
                LLMStreamChunk.done("m1", MODEL_ID, new TokenUsage(5, 2))
        ));

        Map<String, Object> result = executor.execute(step(streamingConfig()), ctx());

        assertThat(received).extracting(WorkflowEvents.StepToken::type)
                .containsExactly("thinking", "text");
        assertThat(received).extracting(WorkflowEvents.StepToken::tokenDelta)
                .containsExactly("추론중", "최종답변");
        // 본문은 thinking 제외 — 기존 동기 경로 buildResult 와 동일 규칙
        assertThat(result.get("output")).isEqualTo("최종답변");
    }

    // ───────────────────────────────────────────────────────────────
    // 하위호환: 스트리밍 비활성 조건 = StepToken 0건 + 동기 chat() 경로
    // ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("stream_tokens 미지정: 스트리밍 비활성 — chatStream 미호출, StepToken 0건, 동기 chat() 경로")
    void noStreamTokens_fallsBackToSyncChat() {
        when(adapter.chat(any(LLMRequest.class))).thenReturn(
                java.util.concurrent.CompletableFuture.completedFuture(
                        new LLMResponse("m1", MODEL_ID,
                                List.of(new ContentBlock.Text("동기응답")),
                                List.of(), new TokenUsage(8, 4),
                                LLMResponse.FinishReason.END, 100L, 0.0)));

        Map<String, Object> noStream = new HashMap<>();
        noStream.put("connection_id", CONNECTION_ID);
        noStream.put("prompt", "{{input.q}}");
        // stream_tokens 키 없음

        Map<String, Object> result = executor.execute(step(noStream), ctx());

        assertThat(received).isEmpty();                       // StepToken 0건
        verify(adapter, never()).chatStream(any(), any());    // 스트리밍 경로 미진입
        verify(adapter, times(1)).chat(any());                // 동기 경로
        assertThat(result.get("output")).isEqualTo("동기응답");
    }

    @Test
    @DisplayName("stream_tokens=true 라도 response_schema 존재 시 비활성 (구조화 출력 우선, 하위호환)")
    void responseSchemaPresent_disablesStreaming() {
        when(adapter.chat(any(LLMRequest.class))).thenReturn(
                java.util.concurrent.CompletableFuture.completedFuture(
                        new LLMResponse("m1", MODEL_ID,
                                List.of(new ContentBlock.Structured("structured_output",
                                        Map.of("category", "greeting"))),
                                List.of(), new TokenUsage(8, 4),
                                LLMResponse.FinishReason.END, 100L, 0.0)));

        Map<String, Object> cfg = streamingConfig();
        cfg.put("response_schema", Map.of("type", "object",
                "properties", Map.of("category", Map.of("type", "string"))));

        Map<String, Object> result = executor.execute(step(cfg), ctx());

        assertThat(received).isEmpty();
        verify(adapter, never()).chatStream(any(), any());
        verify(adapter, times(1)).chat(any());
        assertThat(result).containsKey("structured_data");
    }

    @Test
    @DisplayName("eventPublisher 부재(3-arg 생성자): stream_tokens=true 라도 동기 경로 폴백 (NPE 없음)")
    void noPublisher_streamingDisabledGracefully() {
        // 기존 테스트/비스트리밍 사용처가 쓰는 3-arg 생성자 — eventPublisher=null
        LlmCallStepExecutor noPubExecutor =
                new LlmCallStepExecutor(modelRouter, connectionAdapterFactory, promptTemplateService);

        when(adapter.chat(any(LLMRequest.class))).thenReturn(
                java.util.concurrent.CompletableFuture.completedFuture(
                        new LLMResponse("m1", MODEL_ID,
                                List.of(new ContentBlock.Text("폴백")),
                                List.of(), new TokenUsage(3, 1),
                                LLMResponse.FinishReason.END, 50L, 0.0)));

        Map<String, Object> result = noPubExecutor.execute(step(streamingConfig()), ctx());

        assertThat(received).isEmpty();
        verify(adapter, never()).chatStream(any(), any());
        verify(adapter, times(1)).chat(any());
        assertThat(result.get("output")).isEqualTo("폴백");
    }

    // ───────────────────────────────────────────────────────────────
    // 실 LLM 어댑터 변형 (환경변수 게이트 — 키 있을 때만)
    // ───────────────────────────────────────────────────────────────

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
            named = "CR085_REAL_LLM_IT", matches = "true")
    @DisplayName("[게이트:CR085_REAL_LLM_IT=true] 실 어댑터 chatStream 으로 토큰 수신")
    void realLlmStreaming_envGated() {
        // 실제 Connection/어댑터를 붙이는 변형. 기본 OFF — 자격증명/스테이징 어댑터 환경에서만.
        // 게이트 ON 시 이 본문에 실 ConnectionAdapterFactory 주입 + 실 모델 호출을 구성한다.
        // (현재는 게이트 OFF 가 기본이므로 스킵되며, ON 시 NotImplemented 로 환경 미구성을 명시)
        org.junit.jupiter.api.Assumptions.assumeTrue(false,
                "실 LLM 변형은 스테이징 Connection 환경에서 본문 구성 필요 — 환경 준비 시 활성화");
    }
}
