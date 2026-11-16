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
 * CR-196 TC-TOK-016: 실행기가 계정 토큰 없음, 기한 지남으로 거절하면 cli_runner_unreachable 로 감싸지 않고
 * 토큰 예외로 올리며, 엔진이 감싸 던져도 비실시간 응답은 503 이다.
 */
class CliAccountTokenTest {

    private static final String MISSING = "cli_account_token_missing:/root/.claude-accounts/work01";
    private static final String EXPIRED =
            "cli_account_token_expired:/root/.claude-accounts/work01:expires_at=2026-11-16T08:59+09:00";

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
    void tc_tok_016_chatRunnerBodyWithMissingTokenBecomesTokenFailure() {
        when(client.chat(any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Runner /v1/chat 실패: {\"error\":\"" + MISSING + "\"}"));

        assertThatThrownBy(() -> adapter.chat(request()).get())
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(CliAccountTokenException.class)
                .hasMessage(MISSING);
    }

    @Test
    void tc_tok_016_streamErrorWithExpiredTokenBecomesTokenFailure() {
        doThrow(new RuntimeException("Runner stream error: " + EXPIRED))
                .when(client).chatStream(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());

        assertThatThrownBy(() -> adapter.chatStream(request(), chunk -> {}))
                .isInstanceOf(CliAccountTokenException.class)
                .hasMessage(EXPIRED);
    }

    @Test
    void tc_tok_016_wrappedTokenFailureReturns503() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mvc.perform(get("/token"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value(EXPIRED));
    }

    @RestController
    static class ThrowingController {
        @GetMapping("/token")
        String token() {
            throw new RuntimeException("LLM call failed: x",
                    new ExecutionException(new CliAccountTokenException(EXPIRED, null)));
        }
    }

    @Test
    void headStopsAtJsonQuoteAndIgnoresOtherText() {
        RuntimeException chain = new RuntimeException("outer",
                new IllegalStateException("{\"error\":\"" + MISSING + "\",\"x\":1}"));
        assertThat(CliAccountTokenException.from(chain)).hasMessage(MISSING);
        assertThat(CliAccountTokenException.from(new RuntimeException("no token head"))).isNull();
    }

    private static LLMRequest request() {
        return new LLMRequest("claude-sonnet-4-5",
                List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "hi")),
                null, ModelConfig.defaults(), false, "run-1");
    }
}
