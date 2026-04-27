package com.platform.agent.runner;

import com.platform.agent.runner.dto.RunnerCancelRequest;
import com.platform.agent.runner.dto.RunnerChatRequest;
import com.platform.agent.runner.dto.RunnerChatResponse;
import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.LLMRequest;
import com.platform.llm.model.LLMResponse;
import com.platform.llm.model.TokenUsage;
import com.platform.llm.model.ToolCall;
import com.platform.runner.claudecli.ClaudeCliCommandBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunnerControllerTest {

    private RunnerService service;
    private RunnerProperties props;
    private RunnerController controller;

    @BeforeEach
    void setUp() {
        service = mock(RunnerService.class);
        props = new RunnerProperties();
        props.setEnabled(true);
        props.setApiKey("secret-test-key");
        props.setDefaultModel("claude-sonnet-4-5");
        props.setMaxWorkers(5);
        controller = new RunnerController(service, props);
    }

    private static RunnerChatRequest sampleRequest() {
        RunnerChatRequest req = new RunnerChatRequest();
        req.setRunId("run-1");
        req.setMessages(List.of(Map.of("role", "user", "content", "안녕")));
        return req;
    }

    @Test
    @DisplayName("/v1/chat — X-Api-Key 누락 시 401")
    void apiKeyMissing() {
        RunnerChatRequest req = sampleRequest();
        assertThatThrownBy(() -> controller.chat(null, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Invalid X-Api-Key");
    }

    @Test
    @DisplayName("/v1/chat — X-Api-Key 일치하면 service 호출 + 응답 매핑")
    void chatHappyPath() {
        RunnerChatRequest req = sampleRequest();
        req.setToolMode("AIMBASE");

        LLMResponse llmResp = new LLMResponse(
                "id-1", "claude-sonnet-4-5",
                List.of(new ContentBlock.Text("ok")),
                List.of(),
                new TokenUsage(10, 5),
                LLMResponse.FinishReason.END,
                123L, 0.0);
        when(service.chat(any(LLMRequest.class), any(ClaudeCliCommandBuilder.ToolMode.class), any(), any()))
                .thenReturn(llmResp);

        RunnerChatResponse out = controller.chat("secret-test-key", req);

        assertThat(out.getRunId()).isEqualTo("run-1");
        assertThat(out.getModel()).isEqualTo("claude-sonnet-4-5");
        assertThat(out.getContent()).isEqualTo("ok");
        assertThat(out.getFinishReason()).isEqualTo("END");
        assertThat(out.getUsage()).containsEntry("input_tokens", 10).containsEntry("output_tokens", 5);
        verify(service, times(1)).chat(any(), any(), any(), any());
    }

    @Test
    @DisplayName("/v1/chat — tool_calls 배열이 응답에 그대로 매핑")
    void chatToolCallsMapping() {
        RunnerChatRequest req = sampleRequest();
        LLMResponse llmResp = new LLMResponse(
                "id-1", "claude-sonnet-4-5",
                List.of(),
                List.of(new ToolCall("tu-1", "Read", Map.of("path", "/tmp/a"))),
                new TokenUsage(0, 0),
                LLMResponse.FinishReason.TOOL_USE,
                10L, 0.0);
        when(service.chat(any(), any(), any(), any())).thenReturn(llmResp);

        RunnerChatResponse out = controller.chat("secret-test-key", req);

        assertThat(out.getToolCalls()).hasSize(1);
        assertThat(out.getToolCalls().get(0))
                .containsEntry("id", "tu-1")
                .containsEntry("name", "Read");
    }

    @Test
    @DisplayName("/v1/chat — run_id 누락이면 400")
    void runIdMissing() {
        RunnerChatRequest req = new RunnerChatRequest();
        req.setMessages(List.of(Map.of("role", "user", "content", "안녕")));
        assertThatThrownBy(() -> controller.chat("secret-test-key", req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("run_id required");
    }

    @Test
    @DisplayName("/v1/chat — messages 누락이면 400")
    void messagesMissing() {
        RunnerChatRequest req = new RunnerChatRequest();
        req.setRunId("run-1");
        assertThatThrownBy(() -> controller.chat("secret-test-key", req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("messages required");
    }

    @Test
    @DisplayName("/v1/chat — 잘못된 tool_mode 는 400")
    void invalidToolMode() {
        RunnerChatRequest req = sampleRequest();
        req.setToolMode("INVALID");
        assertThatThrownBy(() -> controller.chat("secret-test-key", req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid tool_mode");
    }

    @Test
    @DisplayName("/v1/cancel — run_id 전달 시 service.cancel 호출")
    void cancelHappyPath() {
        when(service.cancel("run-1")).thenReturn(true);
        RunnerCancelRequest req = new RunnerCancelRequest();
        req.setRunId("run-1");

        Map<String, Object> result = controller.cancel("secret-test-key", req);

        assertThat(result).containsEntry("cancelled", true);
        verify(service).cancel("run-1");
    }

    @Test
    @DisplayName("/v1/cancel — run_id 누락이면 400")
    void cancelMissingRunId() {
        RunnerCancelRequest req = new RunnerCancelRequest();
        assertThatThrownBy(() -> controller.cancel("secret-test-key", req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("run_id required");
    }

    @Test
    @DisplayName("/v1/health — 기본 상태 + 카운터")
    void health() {
        when(service.activeRunCount()).thenReturn(2);
        Map<String, Object> result = controller.health();

        assertThat(result)
                .containsEntry("status", "UP")
                .containsEntry("active_runs", 2)
                .containsEntry("max_workers", 5);
        assertThat((Long) result.get("uptime_seconds")).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("api-key 미설정이면 인증 비활성 (개발/테스트 모드)")
    void apiKeyDisabledMode() {
        props.setApiKey(null);
        Consumer<String> dummy = s -> {};
        // 빈 키여도 헤더 없이 통과
        RunnerChatRequest req = sampleRequest();
        LLMResponse llmResp = new LLMResponse(
                "id-1", "claude-sonnet-4-5",
                List.of(new ContentBlock.Text("ok")),
                List.of(), new TokenUsage(0, 0),
                LLMResponse.FinishReason.END, 1L, 0.0);
        when(service.chat(any(), any(), any(), any())).thenReturn(llmResp);

        RunnerChatResponse out = controller.chat(null, req);
        assertThat(out.getContent()).isEqualTo("ok");
    }
}
