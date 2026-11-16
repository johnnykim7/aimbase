package com.platform.agent.runner;

import com.platform.runner.claudecli.ClaudeAccountToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CR-196 TC-TOK-009, TC-TOK-017 ~ 019. */
class RunnerAccountTokenServiceTest {

    private static final String KEY = "secret-test-key";
    private static final String TOKEN = "sk-ant-oat01-" + "a".repeat(40);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-16T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tmp;

    private RunnerProperties props;
    private AtomicReference<Boolean> answers;
    private AtomicReference<String> askedWith;
    private RunnerAccountTokenService service;

    @BeforeEach
    void setUp() {
        props = new RunnerProperties();
        props.setApiKey(KEY);
        answers = new AtomicReference<>(true);
        askedWith = new AtomicReference<>();
        service = new RunnerAccountTokenService(props, (dir, token) -> {
            askedWith.set(token);
            return answers.get();
        }, CLOCK);
    }

    @Test
    void tc_tok_017_rightValueIsWrittenWith600AndOneYear() throws Exception {
        Path dir = tmp.resolve("work01");

        Map<String, Object> out = service.put(dir.toString(), "  " + TOKEN + "\n", "tenant");

        ClaudeAccountToken saved = ClaudeAccountToken.load(dir, CLOCK);
        assertThat(saved.token()).isEqualTo(TOKEN);
        assertThat(saved.issuedAt()).isEqualTo(OffsetDateTime.now(CLOCK));
        assertThat(saved.expiresAt()).isEqualTo(OffsetDateTime.now(CLOCK).plusDays(365));
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("oauth-token.json"))))
                .isEqualTo("rw-------");
        assertThat(Files.exists(dir.resolve("oauth-token.json.new"))).isFalse();
        assertThat(askedWith.get()).isEqualTo(TOKEN);
        assertThat(out).containsEntry("present", true).containsEntry("managed_by", "tenant")
                .doesNotContainKey("token");
        assertThat(out.toString()).doesNotContain(TOKEN);
    }

    @Test
    void tc_tok_017_wrongShapeIsInvalidWithoutAsking() {
        assertThatThrownBy(() -> service.put(tmp.toString(), "not-a-token", "tenant"))
                .isInstanceOf(RunnerAccountTokenService.TokenRejected.class)
                .hasMessage("account_token_invalid");
        assertThatThrownBy(() -> service.put(tmp.toString(), "sk-ant-oat01-", "tenant"))
                .hasMessage("account_token_invalid");
        assertThatThrownBy(() -> service.put(tmp.toString(), "sk-ant-oat01-ab cd", "tenant"))
                .hasMessage("account_token_invalid");
        assertThat(askedWith.get()).isNull();
    }

    @Test
    void tc_tok_017_unansweredValueKeepsOldFile() throws Exception {
        Path dir = tmp.resolve("work02");
        String old = "sk-ant-oat01-" + "b".repeat(40);
        OffsetDateTime issued = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        ClaudeAccountToken.write(dir, old, issued, issued.plusDays(365));
        answers.set(false);

        assertThatThrownBy(() -> service.put(dir.toString(), TOKEN, "tenant"))
                .isInstanceOf(RunnerAccountTokenService.TokenRejected.class)
                .hasMessage("account_token_rejected")
                .satisfies(e -> assertThat(((RunnerAccountTokenService.TokenRejected) e).status()).isEqualTo(400));

        assertThat(ClaudeAccountToken.load(dir, CLOCK).token()).isEqualTo(old);
    }

    @Test
    void tc_tok_018_platformManagedRefusesTenant() {
        props.setTokenManagedBy("platform");

        assertThatThrownBy(() -> service.put(tmp.toString(), TOKEN, "tenant"))
                .isInstanceOf(RunnerAccountTokenService.TokenRejected.class)
                .hasMessage("account_token_platform_managed")
                .satisfies(e -> assertThat(((RunnerAccountTokenService.TokenRejected) e).status()).isEqualTo(409));
        assertThatThrownBy(() -> service.put(tmp.toString(), TOKEN, null))
                .hasMessage("account_token_platform_managed");
        assertThat(askedWith.get()).isNull();

        assertThat(service.put(tmp.toString(), TOKEN, "platform")).containsEntry("managed_by", "platform");
    }

    @Test
    void tc_tok_018_managedByDefaultsToTenant() {
        assertThat(service.describe(tmp.toString())).containsEntry("managed_by", "tenant");
        props.setTokenManagedBy("PLATFORM");
        assertThat(service.describe(tmp.toString())).containsEntry("managed_by", "platform");
        props.setTokenManagedBy("anything");
        assertThat(service.describe(tmp.toString())).containsEntry("managed_by", "tenant");
    }

    @Test
    void tc_tok_019_verifyEnvDropsOverridingAuthAndPassesTokenOnlyByEnv() {
        Map<String, String> env = new HashMap<>();
        ClaudeAccountToken.OVERRIDING_ENV.forEach(k -> env.put(k, "x"));
        env.put("CLAUDECODE", "1");
        env.put("PATH", "/usr/bin");

        RunnerAccountTokenService.verifyEnv(env, tmp, TOKEN);

        assertThat(env).doesNotContainKeys(ClaudeAccountToken.OVERRIDING_ENV.toArray(String[]::new))
                .doesNotContainKey("CLAUDECODE")
                .containsEntry("CLAUDE_CODE_OAUTH_TOKEN", TOKEN)
                .containsEntry("CLAUDE_CONFIG_DIR", tmp.toString())
                .containsEntry("PATH", "/usr/bin");
    }

    @Test
    void tc_tok_019_cliVerifierUsesFakeCliAndKeepsTokenOutOfArgs() throws Exception {
        Path seen = tmp.resolve("seen.txt");
        Path fake = tmp.resolve("fake-claude.sh");
        Files.writeString(fake, "#!/bin/sh\n"
                + "printf '%s|%s|%s' \"$*\" \"$CLAUDE_CODE_OAUTH_TOKEN\" \"${ANTHROPIC_API_KEY:-none}\" > '" + seen + "'\n"
                + "[ \"$CLAUDE_CODE_OAUTH_TOKEN\" = '" + TOKEN + "' ]\n");
        fake.toFile().setExecutable(true);
        props.setClaudeBinary(fake.toString());
        props.setTokenVerifyModel("claude-haiku-5-5");

        RunnerAccountTokenService.Verifier verifier = RunnerAccountTokenService.cliVerifier(props);

        assertThat(verifier.answers(tmp.resolve("work03"), TOKEN)).isTrue();
        String[] parts = Files.readString(seen).split("\\|");
        assertThat(parts[0]).contains("--model claude-haiku-5-5").doesNotContain(TOKEN);
        assertThat(parts[1]).isEqualTo(TOKEN);
        assertThat(verifier.answers(tmp.resolve("work03"), "sk-ant-oat01-other")).isFalse();
    }

    @Test
    void tc_tok_009_controllerGetAndPutKeepTokenOutAndCheckKey() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new RunnerController(mock(RunnerService.class), props, service)).build();
        Path dir = tmp.resolve("work04");

        mvc.perform(get("/v1/account-token").param("config_dir", dir.toString()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/account-token").param("config_dir", dir.toString())
                        .header(RunnerController.RUNNER_API_KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.present").value(false))
                .andExpect(jsonPath("$.managed_by").value("tenant"));

        String body = "{\"config_dir\":\"" + dir + "\",\"token\":\"" + TOKEN + "\",\"by\":\"tenant\"}";
        MvcResult res = mvc.perform(put("/v1/account-token").header(RunnerController.RUNNER_API_KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.present").value(true))
                .andExpect(jsonPath("$.expires_at").value("2027-11-16T00:00Z"))
                .andReturn();
        assertThat(res.getResponse().getContentAsString()).doesNotContain(TOKEN).doesNotContain("\"token\"");

        mvc.perform(put("/v1/account-token").header(RunnerController.RUNNER_API_KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"config_dir\":\"" + dir + "\",\"token\":\"bad\",\"by\":\"tenant\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("account_token_invalid"));

        props.setTokenManagedBy("platform");
        mvc.perform(put("/v1/account-token").header(RunnerController.RUNNER_API_KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("account_token_platform_managed"));
    }
}
