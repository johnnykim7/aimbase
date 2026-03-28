package com.platform.workflow.step;

import com.platform.llm.ConnectionAdapterFactory;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.model.*;
import com.platform.llm.router.ModelRouter;
import com.platform.workflow.StepContext;
import com.platform.workflow.model.WorkflowStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LlmCallStepExecutorTest {

    @Mock private ModelRouter modelRouter;
    @Mock private ConnectionAdapterFactory connectionAdapterFactory;
    @Mock private LLMAdapter adapter;

    private LlmCallStepExecutor executor;

    private static final String CONNECTION_ID = "conn-001";
    private static final String MODEL_ID = "anthropic/claude-sonnet-4-5";

    @BeforeEach
    void setUp() {
        executor = new LlmCallStepExecutor(modelRouter, connectionAdapterFactory);
        lenient().when(connectionAdapterFactory.getAdapter(CONNECTION_ID)).thenReturn(adapter);
        lenient().when(connectionAdapterFactory.resolveModel(CONNECTION_ID, "auto")).thenReturn(MODEL_ID);
    }

    private StepContext defaultContext() {
        return new StepContext("run-1", "wf-1", "sess-1", Map.of("title", "테스트"), Map.of());
    }

    private WorkflowStep stepWithConfig(Map<String, Object> config) {
        return new WorkflowStep("step-1", "LLM Call", WorkflowStep.StepType.LLM_CALL,
                config, List.of(), null, null, 30000L);
    }

    private Map<String, Object> baseConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("connection_id", CONNECTION_ID);
        config.put("prompt", "분류하세요: {{input.title}}");
        config.put("system", "당신은 분류 전문가입니다.");
        return config;
    }

    private Map<String, Object> baseConfigWithSchema() {
        Map<String, Object> config = baseConfig();
        config.put("response_schema", Map.of(
                "type", "object",
                "properties", Map.of(
                        "category", Map.of("type", "string"),
                        "priority", Map.of("type", "string")
                )
        ));
        return config;
    }

    private LLMResponse makeResponse(LLMResponse.FinishReason reason, int outputTokens) {
        return new LLMResponse("msg-1", MODEL_ID,
                List.of(new ContentBlock.Text("응답 텍스트")),
                List.of(), new TokenUsage(100, outputTokens), reason, 200L, 0.01);
    }

    private LLMResponse makeStructuredResponse(LLMResponse.FinishReason reason,
                                                Map<String, Object> data, int outputTokens) {
        return new LLMResponse("msg-1", MODEL_ID,
                List.of(new ContentBlock.Structured("structured_output", data)),
                List.of(), new TokenUsage(100, outputTokens), reason, 200L, 0.01);
    }

    private void mockAdapterReturn(LLMResponse... responses) {
        var stub = when(adapter.chat(any(LLMRequest.class)));
        for (LLMResponse r : responses) {
            stub = stub.thenReturn(CompletableFuture.completedFuture(r));
        }
    }

    // ═══════════════════════════════════════════════
    // Phase 1: 일반 호출
    // ═══════════════════════════════════════════════

    @Nested
    @DisplayName("Phase 1: 일반 호출 (4096)")
    class Phase1Tests {

        @Test
        @DisplayName("Phase 1 성공 — 텍스트 응답")
        void phase1_textResponse_success() {
            mockAdapterReturn(makeResponse(LLMResponse.FinishReason.END, 500));

            Map<String, Object> result = executor.execute(stepWithConfig(baseConfig()), defaultContext());

            assertThat(result).containsEntry("output", "응답 텍스트");
            assertThat(result).containsEntry("model", MODEL_ID);
            assertThat(result).containsEntry("input_tokens", 100);
            assertThat(result).containsEntry("output_tokens", 500);
            verify(adapter, times(1)).chat(any());
        }

        @Test
        @DisplayName("Phase 1 성공 — structured output 포함")
        void phase1_structuredResponse_success() {
            Map<String, Object> data = Map.of("category", "bug", "priority", "high");
            mockAdapterReturn(makeStructuredResponse(LLMResponse.FinishReason.END, data, 300));

            Map<String, Object> result = executor.execute(stepWithConfig(baseConfigWithSchema()), defaultContext());

            assertThat(result).containsEntry("structured_data", data);
            assertThat(result).containsEntry("model", MODEL_ID);
            verify(adapter, times(1)).chat(any());
        }

        @Test
        @DisplayName("Phase 1 — max_tokens=4096으로 호출 확인")
        void phase1_usesInitialMaxTokens() {
            mockAdapterReturn(makeResponse(LLMResponse.FinishReason.END, 100));

            executor.execute(stepWithConfig(baseConfig()), defaultContext());

            ArgumentCaptor<LLMRequest> captor = ArgumentCaptor.forClass(LLMRequest.class);
            verify(adapter).chat(captor.capture());
            assertThat(captor.getValue().config().maxTokens()).isEqualTo(4096);
        }

        @Test
        @DisplayName("Phase 1 — config ceiling이 4096보다 작으면 ceiling 사용")
        void phase1_respectsConfigCeiling() {
            Map<String, Object> config = baseConfig();
            config.put("max_tokens", 2048);
            mockAdapterReturn(makeResponse(LLMResponse.FinishReason.END, 100));

            executor.execute(stepWithConfig(config), defaultContext());

            ArgumentCaptor<LLMRequest> captor = ArgumentCaptor.forClass(LLMRequest.class);
            verify(adapter).chat(captor.capture());
            assertThat(captor.getValue().config().maxTokens()).isEqualTo(2048);
        }

        @Test
        @DisplayName("Phase 1 — TOOL_USE finishReason도 성공 처리")
        void phase1_toolUseFinishReason_success() {
            mockAdapterReturn(makeResponse(LLMResponse.FinishReason.TOOL_USE, 300));

            Map<String, Object> result = executor.execute(stepWithConfig(baseConfig()), defaultContext());

            assertThat(result).containsKey("output");
            verify(adapter, times(1)).chat(any());
        }
    }

    // ═══════════════════════════════════════════════
    // Phase 2: 에스컬레이션
    // ═══════════════════════════════════════════════

    @Nested
    @DisplayName("Phase 2: 에스컬레이션 (8192)")
    class Phase2Tests {

        @Test
        @DisplayName("Phase 1 MAX_TOKENS → Phase 2 성공")
        void phase1Truncated_phase2Success() {
            LLMResponse truncated = makeResponse(LLMResponse.FinishReason.MAX_TOKENS, 4096);
            LLMResponse success = makeResponse(LLMResponse.FinishReason.END, 6000);
            mockAdapterReturn(truncated, success);

            Map<String, Object> result = executor.execute(stepWithConfig(baseConfig()), defaultContext());

            assertThat(result).containsEntry("output", "응답 텍스트");
            assertThat(result).containsEntry("output_tokens", 6000);
            verify(adapter, times(2)).chat(any());

            // 두 번째 호출이 8192 사용하는지 확인
            ArgumentCaptor<LLMRequest> captor = ArgumentCaptor.forClass(LLMRequest.class);
            verify(adapter, times(2)).chat(captor.capture());
            List<LLMRequest> requests = captor.getAllValues();
            assertThat(requests.get(0).config().maxTokens()).isEqualTo(4096);
            assertThat(requests.get(1).config().maxTokens()).isEqualTo(8192);
        }

        @Test
        @DisplayName("config ceiling=4096이면 에스컬레이션 스킵 → Phase 3")
        void configCeiling4096_skipsEscalation() {
            Map<String, Object> config = baseConfigWithSchema();
            config.put("max_tokens", 4096);

            // Phase 1 잘림 → Phase 2 스킵(ceiling=4096=phase1) → Phase 3
            // Phase 3: planSplit, executePart×2, merge
            LLMResponse truncated = makeResponse(LLMResponse.FinishReason.MAX_TOKENS, 4096);
            LLMResponse planResponse = makeStructuredResponse(LLMResponse.FinishReason.END,
                    Map.of("parts", List.of(
                            Map.of("part_number", 1, "scope", "파트A"),
                            Map.of("part_number", 2, "scope", "파트B")
                    )), 200);
            LLMResponse partResp = makeResponse(LLMResponse.FinishReason.END, 300);
            LLMResponse mergeResp = makeStructuredResponse(LLMResponse.FinishReason.END,
                    Map.of("category", "bug", "priority", "high"), 500);
            mockAdapterReturn(truncated, planResponse, partResp, partResp, mergeResp);

            Map<String, Object> result = executor.execute(stepWithConfig(config), defaultContext());

            assertThat(result).containsEntry("_auto_split", true);
            // Phase 1(1) + Phase 3(plan+2parts+merge=4) = 5
            verify(adapter, times(5)).chat(any());
        }
    }

    // ═══════════════════════════════════════════════
    // Phase 3: 자동분할
    // ═══════════════════════════════════════════════

    @Nested
    @DisplayName("Phase 3: 자동분할")
    class Phase3Tests {

        @Test
        @DisplayName("Phase 1→2→3 전체 흐름 — 3개 파트 분할 성공")
        void fullAutoSplit_3parts() {
            // Phase 1: 잘림
            LLMResponse truncated1 = makeResponse(LLMResponse.FinishReason.MAX_TOKENS, 4096);
            // Phase 2: 잘림
            LLMResponse truncated2 = makeResponse(LLMResponse.FinishReason.MAX_TOKENS, 8192);
            // Phase 3a: 분할 계획 (3파트)
            LLMResponse planResp = makeStructuredResponse(LLMResponse.FinishReason.END,
                    Map.of("parts", List.of(
                            Map.of("part_number", 1, "scope", "Outcome 분류", "description", "카테고리 분류"),
                            Map.of("part_number", 2, "scope", "우선순위 분석", "description", "긴급도 판별"),
                            Map.of("part_number", 3, "scope", "요약 생성", "description", "요약 텍스트")
                    )), 200);
            // Phase 3b: 파트 실행 (3회)
            LLMResponse part1 = makeResponse(LLMResponse.FinishReason.END, 800);
            LLMResponse part2 = makeResponse(LLMResponse.FinishReason.END, 700);
            LLMResponse part3 = makeResponse(LLMResponse.FinishReason.END, 600);
            // Phase 3c: 취합
            Map<String, Object> mergedData = Map.of("category", "bug", "priority", "critical", "summary", "요약");
            LLMResponse mergeResp = makeStructuredResponse(LLMResponse.FinishReason.END, mergedData, 500);

            mockAdapterReturn(truncated1, truncated2, planResp, part1, part2, part3, mergeResp);

            Map<String, Object> result = executor.execute(
                    stepWithConfig(baseConfigWithSchema()), defaultContext());

            assertThat(result).containsEntry("_auto_split", true);
            assertThat(result).containsEntry("_split_parts", 3);
            assertThat(result).containsKey("structured_data");
            @SuppressWarnings("unchecked")
            Map<String, Object> sd = (Map<String, Object>) result.get("structured_data");
            assertThat(sd).containsEntry("category", "bug");
            assertThat(sd).containsEntry("priority", "critical");

            // 총 호출: Phase1(1) + Phase2(1) + plan(1) + parts(3) + merge(1) = 7
            verify(adapter, times(7)).chat(any());
        }

        @Test
        @DisplayName("파트 실행 실패 → 1회 재시도 후 성공")
        void partRetry_success() {
            LLMResponse truncated = makeResponse(LLMResponse.FinishReason.MAX_TOKENS, 4096);
            LLMResponse truncated2 = makeResponse(LLMResponse.FinishReason.MAX_TOKENS, 8192);
            LLMResponse planResp = makeStructuredResponse(LLMResponse.FinishReason.END,
                    Map.of("parts", List.of(
                            Map.of("part_number", 1, "scope", "파트A")
                    )), 100);
            // 파트1: 첫 시도 잘림, 재시도 성공
            LLMResponse partTruncated = makeResponse(LLMResponse.FinishReason.MAX_TOKENS, 4096);
            LLMResponse partSuccess = makeResponse(LLMResponse.FinishReason.END, 500);
            // 취합
            LLMResponse mergeResp = makeStructuredResponse(LLMResponse.FinishReason.END,
                    Map.of("result", "ok"), 300);

            mockAdapterReturn(truncated, truncated2, planResp, partTruncated, partSuccess, mergeResp);

            Map<String, Object> result = executor.execute(
                    stepWithConfig(baseConfigWithSchema()), defaultContext());

            assertThat(result).containsEntry("_auto_split", true);
            // Phase1+Phase2+plan+part(2tries)+merge = 6
            verify(adapter, times(6)).chat(any());
        }

        @Test
        @DisplayName("파트 실행 실패 → 재시도도 실패 → RuntimeException")
        void partRetry_allFail_throws() {
            LLMResponse truncated = makeResponse(LLMResponse.FinishReason.MAX_TOKENS, 4096);
            LLMResponse truncated2 = makeResponse(LLMResponse.FinishReason.MAX_TOKENS, 8192);
            LLMResponse planResp = makeStructuredResponse(LLMResponse.FinishReason.END,
                    Map.of("parts", List.of(
                            Map.of("part_number", 1, "scope", "파트A")
                    )), 100);
            // 파트: 두 번 다 잘림
            LLMResponse partTrunc = makeResponse(LLMResponse.FinishReason.MAX_TOKENS, 4096);

            mockAdapterReturn(truncated, truncated2, planResp, partTrunc, partTrunc);

            assertThatThrownBy(() ->
                    executor.execute(stepWithConfig(baseConfigWithSchema()), defaultContext()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("수동으로 분할");
        }

        @Test
        @DisplayName("취합 잘림 → 에스컬레이션(8192)으로 재시도 성공")
        void mergeEscalation_success() {
            LLMResponse truncated = makeResponse(LLMResponse.FinishReason.MAX_TOKENS, 4096);
            LLMResponse truncated2 = makeResponse(LLMResponse.FinishReason.MAX_TOKENS, 8192);
            LLMResponse planResp = makeStructuredResponse(LLMResponse.FinishReason.END,
                    Map.of("parts", List.of(
                            Map.of("part_number", 1, "scope", "파트A"),
                            Map.of("part_number", 2, "scope", "파트B")
                    )), 100);
            LLMResponse partResp = makeResponse(LLMResponse.FinishReason.END, 500);
            // 취합: 첫 시도 잘림, 에스컬레이션 성공
            LLMResponse mergeTrunc = makeResponse(LLMResponse.FinishReason.MAX_TOKENS, 4096);
            LLMResponse mergeOk = makeStructuredResponse(LLMResponse.FinishReason.END,
                    Map.of("result", "merged"), 700);

            mockAdapterReturn(truncated, truncated2, planResp, partResp, partResp, mergeTrunc, mergeOk);

            Map<String, Object> result = executor.execute(
                    stepWithConfig(baseConfigWithSchema()), defaultContext());

            assertThat(result).containsEntry("_auto_split", true);
            // Phase1+Phase2+plan+2parts+merge(2tries) = 8
            verify(adapter, times(8)).chat(any());
        }

        @Test
        @DisplayName("취합 에스컬레이션도 실패 → RuntimeException")
        void mergeEscalation_fail_throws() {
            LLMResponse truncated = makeResponse(LLMResponse.FinishReason.MAX_TOKENS, 4096);
            LLMResponse truncated2 = makeResponse(LLMResponse.FinishReason.MAX_TOKENS, 8192);
            LLMResponse planResp = makeStructuredResponse(LLMResponse.FinishReason.END,
                    Map.of("parts", List.of(
                            Map.of("part_number", 1, "scope", "파트A")
                    )), 100);
            LLMResponse partResp = makeResponse(LLMResponse.FinishReason.END, 500);
            LLMResponse mergeTrunc = makeResponse(LLMResponse.FinishReason.MAX_TOKENS, 4096);

            mockAdapterReturn(truncated, truncated2, planResp, partResp, mergeTrunc, mergeTrunc);

            assertThatThrownBy(() ->
                    executor.execute(stepWithConfig(baseConfigWithSchema()), defaultContext()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("취합 에스컬레이션");
        }

        @Test
        @DisplayName("분할 계획이 10개 초과 → 10개로 제한")
        void planSplit_limitedTo10Parts() {
            LLMResponse truncated = makeResponse(LLMResponse.FinishReason.MAX_TOKENS, 4096);
            LLMResponse truncated2 = makeResponse(LLMResponse.FinishReason.MAX_TOKENS, 8192);

            // 12개 파트 반환
            List<Map<String, Object>> twelveParts = new java.util.ArrayList<>();
            for (int i = 1; i <= 12; i++) {
                twelveParts.add(Map.of("part_number", i, "scope", "파트" + i));
            }
            LLMResponse planResp = makeStructuredResponse(LLMResponse.FinishReason.END,
                    Map.of("parts", twelveParts), 300);
            LLMResponse partResp = makeResponse(LLMResponse.FinishReason.END, 200);
            LLMResponse mergeResp = makeStructuredResponse(LLMResponse.FinishReason.END,
                    Map.of("result", "ok"), 500);

            // Phase1 + Phase2 + plan + 10 parts + merge = 14
            LLMResponse[] responses = new LLMResponse[14];
            responses[0] = truncated;
            responses[1] = truncated2;
            responses[2] = planResp;
            for (int i = 3; i < 13; i++) responses[i] = partResp;
            responses[13] = mergeResp;
            mockAdapterReturn(responses);

            Map<String, Object> result = executor.execute(
                    stepWithConfig(baseConfigWithSchema()), defaultContext());

            assertThat(result).containsEntry("_split_parts", 10);
            verify(adapter, times(14)).chat(any());
        }

        @Test
        @DisplayName("분할 계획 실패 (parts 없음) → RuntimeException")
        void planSplit_noParts_throws() {
            LLMResponse truncated = makeResponse(LLMResponse.FinishReason.MAX_TOKENS, 4096);
            LLMResponse truncated2 = makeResponse(LLMResponse.FinishReason.MAX_TOKENS, 8192);
            LLMResponse planResp = makeStructuredResponse(LLMResponse.FinishReason.END,
                    Map.of("error", "cannot split"), 100);

            mockAdapterReturn(truncated, truncated2, planResp);

            assertThatThrownBy(() ->
                    executor.execute(stepWithConfig(baseConfigWithSchema()), defaultContext()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("계획 생성 실패");
        }

        @Test
        @DisplayName("분할 계획 빈 배열 → RuntimeException")
        void planSplit_emptyParts_throws() {
            LLMResponse truncated = makeResponse(LLMResponse.FinishReason.MAX_TOKENS, 4096);
            LLMResponse truncated2 = makeResponse(LLMResponse.FinishReason.MAX_TOKENS, 8192);
            LLMResponse planResp = makeStructuredResponse(LLMResponse.FinishReason.END,
                    Map.of("parts", List.of()), 100);

            mockAdapterReturn(truncated, truncated2, planResp);

            assertThatThrownBy(() ->
                    executor.execute(stepWithConfig(baseConfigWithSchema()), defaultContext()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("빈 배열");
        }
    }

    // ═══════════════════════════════════════════════
    // response_schema 없는 경우
    // ═══════════════════════════════════════════════

    @Nested
    @DisplayName("response_schema 없음 — 자동분할 불가")
    class NoSchemaTests {

        @Test
        @DisplayName("Phase 2까지 잘림 + schema 없음 → RuntimeException")
        void noSchema_phase2Truncated_throws() {
            LLMResponse truncated = makeResponse(LLMResponse.FinishReason.MAX_TOKENS, 4096);

            mockAdapterReturn(truncated, truncated);

            assertThatThrownBy(() ->
                    executor.execute(stepWithConfig(baseConfig()), defaultContext()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("response_schema가 없어");
        }
    }

    // ═══════════════════════════════════════════════
    // connection_id 없는 경우 (ModelRouter 폴백)
    // ═══════════════════════════════════════════════

    @Nested
    @DisplayName("ModelRouter 폴백")
    class ModelRouterFallbackTests {

        @Test
        @DisplayName("connection_id 없음 → ModelRouter 사용")
        void noConnectionId_usesModelRouter() {
            when(modelRouter.resolveModelId("auto")).thenReturn(MODEL_ID);
            when(modelRouter.route(any(LLMRequest.class))).thenReturn(adapter);
            mockAdapterReturn(makeResponse(LLMResponse.FinishReason.END, 200));

            Map<String, Object> config = new HashMap<>();
            config.put("prompt", "hello");

            Map<String, Object> result = executor.execute(stepWithConfig(config), defaultContext());

            assertThat(result).containsEntry("output", "응답 텍스트");
            verify(modelRouter).resolveModelId("auto");
            verify(modelRouter).route(any());
            verify(connectionAdapterFactory, never()).getAdapter(any());
        }
    }

    // ═══════════════════════════════════════════════
    // 토큰 집계
    // ═══════════════════════════════════════════════

    @Nested
    @DisplayName("토큰 집계 정확성")
    class TokenAccountingTests {

        @Test
        @DisplayName("자동분할 시 전체 토큰 집계 (plan + parts + merge)")
        void autoSplit_tokenAccounting() {
            // Phase 1: 잘림 (input=100, output=4096)
            LLMResponse truncated = makeResponse(LLMResponse.FinishReason.MAX_TOKENS, 4096);
            // Phase 2: 잘림
            LLMResponse truncated2 = makeResponse(LLMResponse.FinishReason.MAX_TOKENS, 8192);
            // Plan: input=100, output=150
            LLMResponse planResp = new LLMResponse("msg-p", MODEL_ID,
                    List.of(new ContentBlock.Structured("structured_output",
                            Map.of("parts", List.of(
                                    Map.of("part_number", 1, "scope", "A"),
                                    Map.of("part_number", 2, "scope", "B"))))),
                    List.of(), new TokenUsage(100, 150), LLMResponse.FinishReason.END, 100L, 0.001);
            // Part 1: input=200, output=300
            LLMResponse part1 = new LLMResponse("msg-1", MODEL_ID,
                    List.of(new ContentBlock.Text("{\"a\":1}")),
                    List.of(), new TokenUsage(200, 300), LLMResponse.FinishReason.END, 100L, 0.001);
            // Part 2: input=200, output=400
            LLMResponse part2 = new LLMResponse("msg-2", MODEL_ID,
                    List.of(new ContentBlock.Text("{\"b\":2}")),
                    List.of(), new TokenUsage(200, 400), LLMResponse.FinishReason.END, 100L, 0.001);
            // Merge: input=500, output=600
            LLMResponse mergeResp = new LLMResponse("msg-m", MODEL_ID,
                    List.of(new ContentBlock.Structured("structured_output",
                            Map.of("result", "merged"))),
                    List.of(), new TokenUsage(500, 600), LLMResponse.FinishReason.END, 100L, 0.001);

            mockAdapterReturn(truncated, truncated2, planResp, part1, part2, mergeResp);

            Map<String, Object> result = executor.execute(
                    stepWithConfig(baseConfigWithSchema()), defaultContext());

            // 총 input = plan(100) + part1(200) + part2(200) + merge(500) = 1000
            assertThat(result).containsEntry("input_tokens", 1000);
            // 총 output = plan(150) + part1(300) + part2(400) + merge(600) = 1450
            assertThat(result).containsEntry("output_tokens", 1450);
        }
    }

    // ═══════════════════════════════════════════════
    // supports()
    // ═══════════════════════════════════════════════

    @Test
    @DisplayName("supports() — LLM_CALL 타입 반환")
    void supports_returnsLlmCall() {
        assertThat(executor.supports()).isEqualTo(WorkflowStep.StepType.LLM_CALL);
    }
}
