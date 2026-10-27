package com.platform.llm.adapter;

import com.platform.api.GlobalExceptionHandler;
import com.platform.llm.model.LLMRequest;
import com.platform.llm.model.ModelConfig;
import com.platform.llm.model.UnifiedMessage;
import com.platform.service.AgentEndpoint;
import com.platform.service.AgentRegistryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CR-161 TC-CG-123: 단일 커넥션의 CLI 계정 한도는 cli_runner_unreachable 이 아니라 한도 예외로 올라가고,
 * 엔진이 감싸 던져도 비실시간 응답은 503 이다.
 */
class CliAccountLimitedTest {

    private static final String HEAD = "cli_account_limited:resets_at=1793082000:type=five_hour:";

    private ClaudeCliRunnerClient client;
    private ClaudeCliAdapter adapter;

    @BeforeEach
    void setUp() {
        client = mock(ClaudeCliRunnerClient.class);
        AgentRegistryService agentRegistry = mock(AgentRegistryService.class);
        when(agentRegistry.resolveActiveRunner("agent-1"))
                .thenReturn(Optional.of(new AgentEndpoint("agent-1", "http://host:8290", null)));
        adapter = new ClaudeCliAdapter(client, agentRegistry, "claude-sonnet-4-5", "AIMBASE",
                "/accounts/work01", null, "runner-key", null, true);
        RequestContext.setAgentId("agent-1");
    }

    @AfterEach
    void clear() {
        RequestContext.clear();
    }

    @Test
    void tc_cg_123_chatRunnerBodyWithLimitBecomesAccountLimited() {
        when(client.chat(any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Runner /v1/chat 실패: {\"error\":\"" + HEAD + "CLI result reported is_error=true\"}"));

        assertThatThrownBy(() -> adapter.chat(request()).get())
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(CliAccountLimitedException.class)
                .hasMessage(HEAD);
    }

    @Test
    void tc_cg_123_streamErrorWithLimitBecomesAccountLimited() {
        doThrow(new RuntimeException("Runner stream error: cli_account_limited:resets_at=:type=:limit reached"))
                .when(client).chatStream(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());

        assertThatThrownBy(() -> adapter.chatStream(request(), chunk -> {}))
                .isInstanceOf(CliAccountLimitedException.class)
                .hasMessage("cli_account_limited:resets_at=:type=:");
    }

    @Test
    void tc_cg_123_otherRunnerFailureStaysUnreachable() {
        when(client.chat(any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenThrow(new RuntimeException("Runner /v1/chat 호출 실패: connection refused"));

        assertThatThrownBy(() -> adapter.chat(request()).get())
                .cause()
                .isNotInstanceOf(CliAccountLimitedException.class)
                .hasMessageStartingWith("cli_runner_unreachable:AGENT_OFFLINE:");
    }

    @Test
    void tc_cg_123_wrappedLimitReturns503() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mvc.perform(get("/limited"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value(HEAD));
    }

    @RestController
    static class ThrowingController {
        @GetMapping("/limited")
        String limited() {
            // OrchestratorEngine.chat 이 어댑터 실패를 감싸는 모양 그대로.
            throw new RuntimeException("LLM call failed: x",
                    new ExecutionException(new CliAccountLimitedException(HEAD, null)));
        }
    }

    private static LLMRequest request() {
        return new LLMRequest("claude-sonnet-4-5",
                List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "hi")),
                null, ModelConfig.defaults(), false, "run-1");
    }

    @Test
    void headIsFoundInsideNestedCause() {
        RuntimeException chain = new RuntimeException("outer", new IllegalStateException("x " + HEAD + "rest"));
        assertThat(CliAccountLimitedException.from(chain)).hasMessage(HEAD);
        assertThat(CliAccountLimitedException.from(new RuntimeException("no limit here"))).isNull();
    }
}
