package com.platform.agent.runner;

import com.platform.runner.claudecli.ClaudeCliException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CR-161 TC-CG-121: /v1/chat 의 CLI 실패는 본문 error 에 예외 메시지를 그대로 싣는다. */
class RunnerControllerCliFailureTest {

    @Test
    void tc_cg_121_cliFailureBodyCarriesMessage() throws Exception {
        RunnerService service = mock(RunnerService.class);
        RunnerProperties props = new RunnerProperties();
        props.setEnabled(true);
        props.setApiKey("secret-test-key");
        String limited = "cli_account_limited:resets_at=1793082000:type=five_hour:CLI result reported is_error=true";
        when(service.chat(any(), any(), any(), any(), any(), anyBoolean())).thenThrow(new ClaudeCliException(limited));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new RunnerController(service, props)).build();

        mvc.perform(post("/v1/chat")
                        .header(RunnerController.RUNNER_API_KEY_HEADER, "secret-test-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"run_id\":\"run-1\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value(limited));
    }
}
