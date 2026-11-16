package com.platform.runner.claudecli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CR-196 BIZ-283: 실행기 계정의 1년 토큰 파일. */
class ClaudeAccountTokenTest {

    static final String TOKEN = "sk-ant-oat01-unit-test-value";
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-11-16T00:00:00Z"), ZoneId.of("Asia/Seoul"));

    @TempDir Path dir;

    static void writeToken(Path dir, String token, String expiresAt) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(ClaudeAccountToken.FILE_NAME), """
                {"token": "%s", "issued_at": "2026-11-16T06:30:00+09:00", "expires_at": "%s"}
                """.formatted(token, expiresAt));
    }

    @Test
    @DisplayName("TC-TOK-001 맞는 파일이면 토큰과 두 시각을 읽는다")
    void reads_token_and_times() throws IOException {
        writeToken(dir, TOKEN, "2027-11-16T06:30:00+09:00");

        ClaudeAccountToken t = ClaudeAccountToken.load(dir, NOW);

        assertThat(t.token()).isEqualTo(TOKEN);
        assertThat(t.issuedAt()).hasToString("2026-11-16T06:30+09:00");
        assertThat(t.expiresAt()).hasToString("2027-11-16T06:30+09:00");
    }

    @Test
    @DisplayName("TC-TOK-001 없음, 깨진 JSON, 접두 틀림, token 칸 없음은 넷 다 missing")
    void missing_or_malformed_is_missing() throws IOException {
        String missing = ClaudeAccountToken.MISSING_PREFIX + dir;
        assertThatThrownBy(() -> ClaudeAccountToken.load(dir, NOW))
                .isInstanceOf(ClaudeCliException.class).hasMessage(missing);

        Files.writeString(dir.resolve(ClaudeAccountToken.FILE_NAME), "{not json");
        assertThatThrownBy(() -> ClaudeAccountToken.load(dir, NOW)).hasMessage(missing);

        writeToken(dir, "sk-ant-api03-a-console-key", "2027-11-16T06:30:00+09:00");
        assertThatThrownBy(() -> ClaudeAccountToken.load(dir, NOW)).hasMessage(missing);

        Files.writeString(dir.resolve(ClaudeAccountToken.FILE_NAME),
                "{\"issued_at\": \"2026-11-16T06:30:00+09:00\", \"expires_at\": \"2027-11-16T06:30:00+09:00\"}");
        assertThatThrownBy(() -> ClaudeAccountToken.load(dir, NOW)).hasMessage(missing);
    }

    @Test
    @DisplayName("TC-TOK-002 기한 1분 뒤면 통과하고 1분 전에 지났으면 expired")
    void expiry_is_checked_against_the_clock() throws IOException {
        writeToken(dir, TOKEN, "2026-11-16T09:01:00+09:00");
        assertThat(ClaudeAccountToken.load(dir, NOW).token()).isEqualTo(TOKEN);

        writeToken(dir, TOKEN, "2026-11-16T08:59:00+09:00");
        assertThatThrownBy(() -> ClaudeAccountToken.load(dir, NOW))
                .isInstanceOf(ClaudeCliException.class)
                .hasMessage(ClaudeAccountToken.EXPIRED_PREFIX + dir + ":expires_at=2026-11-16T08:59+09:00");
    }

    @Test
    @DisplayName("TC-TOK-003 계정 폴더는 config_dir, CLAUDE_CONFIG_DIR, ~/.claude 순서")
    void account_dir_follows_the_cli_order() {
        assertThat(ClaudeAccountToken.accountDir("/a/work01", "/env/dir", "/home/u")).isEqualTo(Path.of("/a/work01"));
        assertThat(ClaudeAccountToken.accountDir(" ", "/env/dir", "/home/u")).isEqualTo(Path.of("/env/dir"));
        assertThat(ClaudeAccountToken.accountDir(null, null, "/home/u")).isEqualTo(Path.of("/home/u/.claude"));
    }

    @Test
    @DisplayName("TC-TOK-005 실패 메시지와 문자열 표현에 토큰 값이 없다")
    void token_value_never_leaks() throws IOException {
        writeToken(dir, TOKEN, "2026-11-16T08:59:00+09:00");
        assertThatThrownBy(() -> ClaudeAccountToken.load(dir, NOW)).message().doesNotContain(TOKEN);

        writeToken(dir, TOKEN, "2027-11-16T06:30:00+09:00");
        assertThat(ClaudeAccountToken.load(dir, NOW).toString()).doesNotContain(TOKEN);
    }
}
