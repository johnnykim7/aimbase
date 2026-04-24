package com.platform.llm.claudecli;

import com.platform.llm.model.LLMResponse;
import com.platform.llm.model.UnifiedMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CR-050 Phase 1 단위 테스트.
 *
 * 실제 claude 바이너리 대신 bash 스텁 스크립트를 사용해 stream-json 프로토콜을 에뮬레이트한다.
 * 스텁은 stdin NDJSON을 받아 stdout으로 system/assistant/result 이벤트를 NDJSON으로 내보낸다.
 */
class ClaudeCliWorkerTest {

    @TempDir
    Path tempDir;

    private Path binary;
    private ClaudeCliWorker worker;

    @BeforeEach
    void setup() throws IOException {
        // 기본 스텁: 사용자 메시지가 1줄 들어올 때마다 init(첫 호출만) + assistant + result 방출
        binary = writeStub("""
                #!/bin/bash
                echo '{"type":"system","subtype":"init","session_id":"sess-stub-001"}'
                FIRST=1
                while IFS= read -r line; do
                  if [ -z "$line" ]; then continue; fi
                  if [ "$FIRST" = "1" ]; then
                    FIRST=0
                  fi
                  echo '{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"hello"}]}}'
                  echo '{"type":"result","subtype":"success","result":"hello","session_id":"sess-stub-001","total_cost_usd":0.0007,"usage":{"input_tokens":12,"output_tokens":3,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}'
                done
                """);
    }

    @AfterEach
    void teardown() {
        if (worker != null) worker.close();
    }

    @Test
    void turnFirst_returns_assistant_text_and_usage() throws IOException {
        worker = new ClaudeCliWorker(
                binary.toString(), null, null, false, null, Duration.ofSeconds(10));
        worker.start();

        LLMResponse resp = worker.turnFirst(List.of(
                UnifiedMessage.ofText(UnifiedMessage.Role.USER, "hello?")));

        assertThat(resp.textContent()).isEqualTo("hello");
        assertThat(resp.usage().inputTokens()).isEqualTo(12);
        assertThat(resp.usage().outputTokens()).isEqualTo(3);
        assertThat(resp.costUsd()).isEqualTo(0.0007);
        assertThat(resp.finishReason()).isEqualTo(LLMResponse.FinishReason.END);
        assertThat(worker.getSessionId()).isEqualTo("sess-stub-001");
    }

    @Test
    void subsequent_turn_requires_turnFirst() throws IOException {
        worker = new ClaudeCliWorker(
                binary.toString(), null, null, false, null, Duration.ofSeconds(10));
        worker.start();

        assertThatThrownBy(() -> worker.turn(
                UnifiedMessage.ofText(UnifiedMessage.Role.USER, "hi")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("turnFirst");
    }

    @Test
    void multi_turn_sends_only_last_user_message() throws IOException {
        worker = new ClaudeCliWorker(
                binary.toString(), null, null, false, null, Duration.ofSeconds(10));
        worker.start();

        worker.turnFirst(List.of(
                UnifiedMessage.ofText(UnifiedMessage.Role.USER, "turn-1")));
        LLMResponse second = worker.turn(
                UnifiedMessage.ofText(UnifiedMessage.Role.USER, "turn-2"));

        assertThat(second.textContent()).isEqualTo("hello");
    }

    @Test
    void turn_timeout_throws_timeout_exception() throws IOException {
        // 응답을 보내지 않는 스텁
        Path hang = writeStub("""
                #!/bin/bash
                echo '{"type":"system","subtype":"init","session_id":"sess-hang"}'
                sleep 10
                """);
        worker = new ClaudeCliWorker(
                hang.toString(), null, null, false, null, Duration.ofSeconds(1));
        worker.start();

        assertThatThrownBy(() -> worker.turnFirst(List.of(
                UnifiedMessage.ofText(UnifiedMessage.Role.USER, "hi"))))
                .isInstanceOf(ClaudeCliTimeoutException.class);
    }

    @Test
    void crash_during_turn_throws_exception() throws IOException {
        Path crash = writeStub("""
                #!/bin/bash
                echo '{"type":"system","subtype":"init","session_id":"sess-crash"}'
                exit 7
                """);
        worker = new ClaudeCliWorker(
                crash.toString(), null, null, false, null, Duration.ofSeconds(5));
        worker.start();

        assertThatThrownBy(() -> worker.turnFirst(List.of(
                UnifiedMessage.ofText(UnifiedMessage.Role.USER, "hi"))))
                .isInstanceOf(ClaudeCliException.class);
    }

    @Test
    void close_terminates_process() throws IOException {
        worker = new ClaudeCliWorker(
                binary.toString(), null, null, false, null, Duration.ofSeconds(10));
        worker.start();
        assertThat(worker.isAlive()).isTrue();

        worker.close();
        // close 후 isAlive는 false 여야 한다 (destroyForcibly 포함).
        // 프로세스 정리는 비동기이므로 최대 2초 대기.
        long deadline = System.currentTimeMillis() + 2000;
        while (worker.isAlive() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        }
        assertThat(worker.isAlive()).isFalse();
    }

    private Path writeStub(String body) throws IOException {
        Path p = tempDir.resolve("claude-stub-" + System.nanoTime() + ".sh");
        Files.writeString(p, body);
        try {
            Files.setPosixFilePermissions(p, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException ignored) {
            // Windows — 테스트 자체가 POSIX 가정이므로 skip 되더라도 무해
        }
        return p;
    }

    @SuppressWarnings("unused")
    private static Set<PosixFilePermission> exec() {
        return EnumSet.of(PosixFilePermission.OWNER_EXECUTE);
    }
}
