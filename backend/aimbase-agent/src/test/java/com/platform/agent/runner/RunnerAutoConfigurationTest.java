package com.platform.agent.runner;

import com.platform.runner.claudecli.ClaudeAccountToken;
import com.platform.runner.claudecli.ClaudeCliException;
import com.platform.runner.claudecli.ClaudeCliWorkerPool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CR-196 BIZ-283: 실행기 모드의 워커 공장은 계정의 1년 토큰이 있어야 워커를 만든다. */
class RunnerAutoConfigurationTest {

    private static final Clock NOW = Clock.fixed(Instant.parse("2026-11-16T00:00:00Z"), ZoneId.of("Asia/Seoul"));

    @TempDir Path dir;

    @Test
    @DisplayName("TC-TOK-006 토큰 없는 계정은 워커를 만들지 않고, 기한이 지났으면 expired")
    void runner_factory_requires_account_token() throws Exception {
        ClaudeCliWorkerPool.WorkerFactory factory =
                RunnerAutoConfiguration.workerFactory(new RunnerProperties(), null, NOW);

        assertThatThrownBy(() -> factory.create(null, null, false, dir.toString()))
                .isInstanceOf(ClaudeCliException.class)
                .hasMessage(ClaudeAccountToken.MISSING_PREFIX + dir);

        write("2026-11-16T08:00:00+09:00");
        assertThatThrownBy(() -> factory.create(null, null, false, dir.toString()))
                .hasMessageStartingWith(ClaudeAccountToken.EXPIRED_PREFIX + dir + ":expires_at=");

        write("2027-11-16T06:30:00+09:00");
        assertThat(factory.create(null, null, false, dir.toString())).isNotNull();
    }

    private void write(String expiresAt) throws Exception {
        Files.writeString(dir.resolve(ClaudeAccountToken.FILE_NAME), """
                {"token": "sk-ant-oat01-unit", "issued_at": "2026-11-16T06:30:00+09:00", "expires_at": "%s"}
                """.formatted(expiresAt));
    }
}
