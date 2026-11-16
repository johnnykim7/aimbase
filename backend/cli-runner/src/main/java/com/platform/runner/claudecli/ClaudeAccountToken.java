package com.platform.runner.claudecli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        ClaudeAccountToken found = read(accountDir);
        if (found == null) {
            throw new ClaudeCliException(MISSING_PREFIX + accountDir);
        }
        if (!found.expiresAt().toInstant().isAfter(clock.instant())) {
            throw new ClaudeCliException(EXPIRED_PREFIX + accountDir + ":expires_at=" + found.expiresAt());
        }
        return found;
    }

    /** 파일이 없거나 모양이 틀리면 null. 기한은 보지 않는다. */
    static ClaudeAccountToken read(Path accountDir) {
        JsonNode node;
        try {
            node = MAPPER.readTree(Files.readString(accountDir.resolve(FILE_NAME)));
        } catch (IOException e) {
            return null;
        }
        String token = text(node, "token");
        if (!looksLikeToken(token)) return null;
        try {
            return new ClaudeAccountToken(token,
                    OffsetDateTime.parse(text(node, "issued_at")),
                    OffsetDateTime.parse(text(node, "expires_at")));
        } catch (NullPointerException | DateTimeParseException e) {
            return null;
        }
    }

    public static boolean looksLikeToken(String token) {
        return token != null && token.startsWith(TOKEN_PREFIX) && token.length() > TOKEN_PREFIX.length()
                && token.chars().noneMatch(Character::isWhitespace);
    }

    /**
     * CR-196 PRD-646: 화면과 만료 확인에 보여 줄 상태. 토큰 값은 싣지 않고, 기한이 지난 파일도 present 로 본다.
     * 남은 날은 받는 쪽이 자기 시계로 센다.
     */
    public static Map<String, Object> describe(Path accountDir) {
        ClaudeAccountToken found = read(accountDir);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("config_dir", accountDir.toString());
        out.put("present", found != null);
        out.put("issued_at", found != null ? found.issuedAt().toString() : null);
        out.put("expires_at", found != null ? found.expiresAt().toString() : null);
        return out;
    }

    /**
     * 같은 폴더의 임시 파일에 600 으로 쓰고 이름을 바꿔 갈아 끼운다. 도중에 멈춰도 쓰던 토큰 파일은 남는다.
     * 실행기가 막 띄우는 워커는 앞 파일이나 새 파일 중 하나를 온전히 읽는다.
     */
    public static void write(Path accountDir, String token, OffsetDateTime issuedAt, OffsetDateTime expiresAt)
            throws IOException {
        if (!looksLikeToken(token)) throw new IllegalArgumentException("token shape");
        Files.createDirectories(accountDir);
        Map<String, String> body = new LinkedHashMap<>();
        body.put("token", token);
        body.put("issued_at", issuedAt.toString());
        body.put("expires_at", expiresAt.toString());
        Path tmp = accountDir.resolve(FILE_NAME + ".new");
        Files.deleteIfExists(tmp);
        try {
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            Files.createFile(tmp);
        } catch (FileAlreadyExistsException e) {
            throw new IOException("token file is being written", e);
        }
        Files.writeString(tmp, MAPPER.writeValueAsString(body) + "\n");
        try {
            Files.move(tmp, accountDir.resolve(FILE_NAME),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, accountDir.resolve(FILE_NAME), StandardCopyOption.REPLACE_EXISTING);
        }
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
