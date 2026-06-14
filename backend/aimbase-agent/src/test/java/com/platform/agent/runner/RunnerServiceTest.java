package com.platform.agent.runner;

import com.platform.llm.model.LLMRequest;
import com.platform.llm.model.LLMResponse;
import com.platform.llm.model.ModelConfig;
import com.platform.llm.model.UnifiedMessage;
import com.platform.runner.claudecli.ClaudeCliCommandBuilder;
import com.platform.runner.claudecli.ClaudeCliWorker;
import com.platform.runner.claudecli.ClaudeCliWorkerPool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CR-109: turn 예외(특히 turn timeout) 시 Worker 가 정리되어 좀비 CLI 프로세스가 누수되지 않는지 검증.
 */
class RunnerServiceTest {

    private ClaudeCliWorkerPool pool;
    private ClaudeCliWorker worker;
    private RunnerService service;

    @BeforeEach
    void setUp() {
        pool = mock(ClaudeCliWorkerPool.class);
        worker = mock(ClaudeCliWorker.class);
        service = new RunnerService(pool, "claude-sonnet-4-5");
        // 7-인자 오버로드를 정확히 타겟 — doReturn 스타일로 컴파일타임 오버로드 모호성 회피.
        org.mockito.Mockito.doReturn(worker).when(pool).getOrCreateMain(
                anyString(), nullable(String.class), nullable(String.class),
                nullable(String.class), nullable(ClaudeCliCommandBuilder.ToolMode.class),
                ArgumentMatchers.<List<String>>any(), nullable(String.class));
    }

    private static LLMRequest request(String runId) {
        return new LLMRequest(
                "claude-sonnet-4-5",
                List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "안녕")),
                null, ModelConfig.defaults(), false, runId);
    }

    @Test
    @DisplayName("CR-109: 첫 턴 timeout 예외 시 shutdownForRun 호출 — 좀비 워커 정리")
    void firstTurnTimeoutClosesWorker() {
        when(worker.turnFirst(any()))
                .thenThrow(new RuntimeException("turn timeout after 300s"));

        assertThatThrownBy(() -> service.chat(request("run-1"), ClaudeCliCommandBuilder.ToolMode.AIMBASE,
                null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("timeout");

        // 핵심: 예외 시 워커 정리를 위한 shutdownForRun 호출
        verify(pool, times(1)).shutdownForRun("run-1");
    }

    @Test
    @DisplayName("CR-109: timeout 후 같은 runId 재호출은 다시 첫 턴 경로로 재진입(마커 회수 → 재시도 가능)")
    void retryAfterTimeoutReinitializes() {
        // 1차 turnFirst → 예외, 2차 turnFirst → 정상. 연속 스텁으로 호출 회차별 동작 지정.
        LLMResponse ok = new LLMResponse(
                "run-1", "claude-sonnet-4-5",
                List.of(new com.platform.llm.model.ContentBlock.Text("ok")),
                List.of(), new com.platform.llm.model.TokenUsage(0, 0),
                LLMResponse.FinishReason.END, 1L, 0.0);
        when(worker.turnFirst(any()))
                .thenThrow(new RuntimeException("turn timeout after 300s"))
                .thenReturn(ok);

        // 1차: timeout → 정리
        assertThatThrownBy(() -> service.chat(request("run-1"), ClaudeCliCommandBuilder.ToolMode.AIMBASE,
                null, null))
                .isInstanceOf(RuntimeException.class);
        verify(pool, times(1)).shutdownForRun("run-1");

        // 2차: firstTurnDone 마커가 회수되어 다시 turnFirst(첫 턴) 경로로 진입 → 정상 응답
        LLMResponse resp = service.chat(request("run-1"), ClaudeCliCommandBuilder.ToolMode.AIMBASE, null, null);
        verify(worker, times(2)).turnFirst(any());
        assertThatNoException(resp);
    }

    @Test
    @DisplayName("CR-109: 정상 완료 시에는 shutdownForRun 호출 안 함 (정리는 run 종료 훅 책임)")
    void happyPathDoesNotShutdown() {
        LLMResponse ok = new LLMResponse(
                "run-1", "claude-sonnet-4-5",
                List.of(new com.platform.llm.model.ContentBlock.Text("ok")),
                List.of(), new com.platform.llm.model.TokenUsage(0, 0),
                LLMResponse.FinishReason.END, 1L, 0.0);
        when(worker.turnFirst(any())).thenReturn(ok);

        service.chat(request("run-1"), ClaudeCliCommandBuilder.ToolMode.AIMBASE, null, null);

        verify(pool, times(0)).shutdownForRun(eq("run-1"));
    }

    private static void assertThatNoException(LLMResponse resp) {
        org.assertj.core.api.Assertions.assertThat(resp).isNotNull();
        org.assertj.core.api.Assertions.assertThat(resp.textContent()).isEqualTo("ok");
    }
}
