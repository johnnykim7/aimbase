package com.platform.agent.runner;

import com.platform.runner.claudecli.ClaudeAccountToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * CR-196 PRD-646, BIZ-289: 화면에서 넣은 1년 토큰을 이 실행기의 계정 폴더에 적는다.
 *
 * <p>적기 전에 그 값으로 짧은 질문 하나를 CLI 에 던져 답이 오는지 본다. 남의 계정이나 끊긴 값을 적으면 그 폴더의
 * 다음 질문부터 모두 실패하기 때문이다. 토큰 값은 기록과 응답에 싣지 않는다.
 */
public class RunnerAccountTokenService {

    private static final Logger log = LoggerFactory.getLogger(RunnerAccountTokenService.class);

    public static final String MANAGED_BY_TENANT = "tenant";
    public static final String MANAGED_BY_PLATFORM = "platform";
    public static final String ERR_INVALID = "account_token_invalid";
    public static final String ERR_REJECTED = "account_token_rejected";
    public static final String ERR_PLATFORM_MANAGED = "account_token_platform_managed";

    /** 확인 질문. 답 내용은 보지 않고 CLI 가 정상으로 끝났는지만 본다. */
    static final String VERIFY_PROMPT = "ok 라고만 답하세요";

    /** 그 값으로 CLI 가 답하는지 본다. 시험에서는 가짜로 바꾼다. */
    @FunctionalInterface
    public interface Verifier {
        boolean answers(Path accountDir, String token);
    }

    /** 이 실행기 계정 폴더의 넣기를 거절한 이유. message 는 오류 코드다. */
    public static class TokenRejected extends RuntimeException {
        private final int status;

        public TokenRejected(int status, String code) {
            super(code);
            this.status = status;
        }

        public int status() { return status; }
    }

    private final RunnerProperties props;
    private final Verifier verifier;
    private final Clock clock;
    private final Map<Path, ReentrantLock> locks = new ConcurrentHashMap<>();

    public RunnerAccountTokenService(RunnerProperties props, Clock clock) {
        this(props, cliVerifier(props), clock);
    }

    RunnerAccountTokenService(RunnerProperties props, Verifier verifier, Clock clock) {
        this.props = props;
        this.verifier = verifier;
        this.clock = clock;
    }

    public Map<String, Object> describe(String configDir) {
        Map<String, Object> out = ClaudeAccountToken.describe(ClaudeAccountToken.accountDir(configDir));
        out.put("managed_by", managedBy());
        return out;
    }

    /**
     * @param by api 가 정한 넣는 쪽. tenant 는 테넌트 관리자, platform 은 임퍼소네이션한 플랫폼 관리자다
     */
    public Map<String, Object> put(String configDir, String token, String by) {
        if (MANAGED_BY_PLATFORM.equals(managedBy()) && !MANAGED_BY_PLATFORM.equals(by)) {
            throw new TokenRejected(409, ERR_PLATFORM_MANAGED);
        }
        String value = token != null ? token.strip() : null;
        if (!ClaudeAccountToken.looksLikeToken(value)) {
            throw new TokenRejected(400, ERR_INVALID);
        }
        Path dir = ClaudeAccountToken.accountDir(configDir);
        ReentrantLock lock = locks.computeIfAbsent(dir, d -> new ReentrantLock());
        lock.lock();
        try {
            if (!verifier.answers(dir, value)) {
                log.warn("[CR-196] 1년 토큰 확인 질문 실패, 파일은 그대로: dir={}, by={}", dir, by);
                throw new TokenRejected(400, ERR_REJECTED);
            }
            OffsetDateTime now = OffsetDateTime.now(clock);
            OffsetDateTime expiresAt = now.plusDays(props.getTokenDays());
            try {
                ClaudeAccountToken.write(dir, value, now, expiresAt);
            } catch (IOException e) {
                log.error("[CR-196] 1년 토큰 파일 쓰기 실패: dir={}, {}", dir, e.getClass().getSimpleName());
                throw new IllegalStateException("account_token_write_failed:" + dir);
            }
            log.info("[CR-196] 1년 토큰 넣음: dir={}, by={}, expires_at={}", dir, by, expiresAt);
        } finally {
            lock.unlock();
        }
        return describe(configDir);
    }

    String managedBy() {
        String v = props.getTokenManagedBy();
        return MANAGED_BY_PLATFORM.equalsIgnoreCase(v != null ? v.strip() : null)
                ? MANAGED_BY_PLATFORM : MANAGED_BY_TENANT;
    }

    /** 워커와 같은 환경(앞서는 인증 다섯을 뺀다)으로 그 폴더에서 CLI 를 한 번 띄운다. 토큰은 환경변수로만 넘긴다. */
    static Verifier cliVerifier(RunnerProperties props) {
        return (dir, token) -> {
            List<String> cmd = new ArrayList<>(List.of(props.getClaudeBinary(), "-p", VERIFY_PROMPT,
                    "--model", props.getTokenVerifyModel()));
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            verifyEnv(pb.environment(), dir, token);
            Duration timeout = Duration.ofSeconds(props.getTokenVerifyTimeoutSeconds());
            Process p = null;
            try {
                p = pb.start();
                p.getOutputStream().close();
                InputStream in = p.getInputStream();
                Process proc = p;
                Thread drain = Thread.ofVirtual().start(() -> {
                    try { in.readAllBytes(); } catch (IOException ignored) { }
                });
                if (!proc.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    log.warn("[CR-196] 확인 질문이 {}초 안에 끝나지 않음: dir={}", timeout.toSeconds(), dir);
                    return false;
                }
                drain.join(Duration.ofSeconds(2));
                int code = proc.exitValue();
                if (code != 0) log.warn("[CR-196] 확인 질문 종료 코드 {}: dir={}", code, dir);
                return code == 0;
            } catch (IOException e) {
                log.warn("[CR-196] 확인 질문을 띄우지 못함: dir={}, {}", dir, e.getClass().getSimpleName());
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } finally {
                if (p != null && p.isAlive()) p.destroyForcibly();
            }
        };
    }

    static void verifyEnv(Map<String, String> env, Path dir, String token) {
        ClaudeAccountToken.OVERRIDING_ENV.forEach(env::remove);
        env.remove("CLAUDECODE");
        env.put("CLAUDE_CONFIG_DIR", dir.toString());
        env.put(ClaudeAccountToken.ENV_NAME, token);
    }
}
