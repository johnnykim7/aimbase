package com.platform.runner.claudecli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * CR-196 BIZ-283: 실행기 계정의 1년 토큰(claude setup-token).
 *
 * <p>계정 폴더의 {@value #FILE_NAME} 이 유일한 원천이다. 실행기는 이 값을 {@value #ENV_NAME} 으로 CLI 에 넘기고,
 * 로그인 파일(.credentials.json)은 보지 않는다. /login 인증은 계정 하나를 여러 CLI 가 같이 쓰면 갱신이 어긋나 끊긴다.
 *
 * <p>토큰 자체는 기한을 알려 주지 않으므로 넣을 때 적은 expires_at 으로 만료를 판단한다.
 */
public record ClaudeAccountToken(String token, OffsetDateTime issuedAt, OffsetDateTime expiresAt) {

    public static final String FILE_NAME = "oauth-token.json";
    public static final String TOKEN_PREFIX = "sk-ant-oat01-";
    public static final String ENV_NAME = "CLAUDE_CODE_OAUTH_TOKEN";
    public static final String MISSING_PREFIX = "cli_account_token_missing:";
    public static final String EXPIRED_PREFIX = "cli_account_token_expired:";

    /**
     * CLI 공식 인증 순서에서 {@value #ENV_NAME} 보다 앞서는 값. 실행기 환경에 있어도 CLI 에 넘기지 않아야
     * 넣은 1년 토큰이 실제로 쓰인다.
     */
    public static final List<String> OVERRIDING_ENV = List.of(
            "ANTHROPIC_AUTH_TOKEN",
            "ANTHROPIC_API_KEY",
            "CLAUDE_CODE_USE_BEDROCK",
            "CLAUDE_CODE_USE_VERTEX",
            "CLAUDE_CODE_USE_FOUNDRY");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 실행기가 띄우는 CLI 와 같은 순서로 계정 폴더를 정한다. 연결 config_dir, CLAUDE_CONFIG_DIR, ~/.claude. */
    public static Path accountDir(String configDir) {
        return accountDir(configDir, System.getenv("CLAUDE_CONFIG_DIR"), System.getProperty("user.home"));
    }

    static Path accountDir(String configDir, String envConfigDir, String userHome) {
        if (configDir != null && !configDir.isBlank()) return Path.of(configDir);
        if (envConfigDir != null && !envConfigDir.isBlank()) return Path.of(envConfigDir);
        return Path.of(userHome, ".claude");
    }

    /**
     * 계정 폴더의 토큰을 읽고 기한을 본다. 없거나 모양이 틀리면 missing, 기한이 지났으면 expired 로 실패한다.
     * 오류 메시지에 토큰 값을 싣지 않는다.
     */
    public static ClaudeAccountToken load(String configDir, Clock clock) {
        return load(accountDir(configDir), clock);
    }

    public static ClaudeAccountToken load(Path accountDir, Clock clock) {
        Path file = accountDir.resolve(FILE_NAME);
        JsonNode node;
        try {
            node = MAPPER.readTree(Files.readString(file));
        } catch (IOException e) {
            throw new ClaudeCliException(MISSING_PREFIX + accountDir);
        }
        String token = text(node, "token");
        if (token == null || !token.startsWith(TOKEN_PREFIX) || token.length() == TOKEN_PREFIX.length()) {
            throw new ClaudeCliException(MISSING_PREFIX + accountDir);
        }
        OffsetDateTime issuedAt;
        OffsetDateTime expiresAt;
        try {
            issuedAt = OffsetDateTime.parse(text(node, "issued_at"));
            expiresAt = OffsetDateTime.parse(text(node, "expires_at"));
        } catch (NullPointerException | DateTimeParseException e) {
            throw new ClaudeCliException(MISSING_PREFIX + accountDir);
        }
        if (!expiresAt.toInstant().isAfter(clock.instant())) {
            throw new ClaudeCliException(EXPIRED_PREFIX + accountDir + ":expires_at=" + expiresAt);
        }
        return new ClaudeAccountToken(token, issuedAt, expiresAt);
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) return null;
        JsonNode v = node.get(field);
        return v != null && v.isTextual() ? v.asText().strip() : null;
    }

    /** 기록에 찍혀도 토큰이 새지 않게 값은 뺀다. */
    @Override
    public String toString() {
        return "ClaudeAccountToken[issuedAt=" + issuedAt + ", expiresAt=" + expiresAt + "]";
    }
}
