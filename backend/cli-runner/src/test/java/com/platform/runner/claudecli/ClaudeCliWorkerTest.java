package com.platform.runner.claudecli;

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

    @Test
    void cr104_setAllowedTools_injects_mcp_server_prefixed_allowedTools_flag() throws IOException {
        // stub 가 받은 전체 명령 인자($@)를 파일에 덤프 → buildCommand 의 prefix 변환 검증
        Path argsDump = tempDir.resolve("args-" + System.nanoTime() + ".txt");
        Path argStub = writeStub("""
                #!/bin/bash
                echo "$@" > '%s'
                echo '{"type":"system","subtype":"init","session_id":"sess-args"}'
                while IFS= read -r line; do
                  if [ -z "$line" ]; then continue; fi
                  echo '{"type":"result","subtype":"success","result":"ok","session_id":"sess-args","total_cost_usd":0.0,"usage":{"input_tokens":1,"output_tokens":1}}'
                done
                """.formatted(argsDump.toString()));

        worker = new ClaudeCliWorker(argStub.toString(), null, null, false, null, Duration.ofSeconds(10));
        worker.setAllowedTools(List.of("file_write", "builtin_grep"));
        worker.start();
        worker.turnFirst(List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "go")));

        String args = Files.readString(argsDump);
        // CR-104: 원본 도구명 → mcp__aimbase-server__<tool> 변환 + --allowedTools 플래그
        assertThat(args).contains("--allowedTools mcp__aimbase-server__file_write");
        assertThat(args).contains("--allowedTools mcp__aimbase-server__builtin_grep");
    }

    @Test
    void cr104_no_allowedTools_means_no_allowedTools_flag() throws IOException {
        Path argsDump = tempDir.resolve("args2-" + System.nanoTime() + ".txt");
        Path argStub = writeStub("""
                #!/bin/bash
                echo "$@" > '%s'
                echo '{"type":"system","subtype":"init","session_id":"sess-args2"}'
                while IFS= read -r line; do
                  if [ -z "$line" ]; then continue; fi
                  echo '{"type":"result","subtype":"success","result":"ok","session_id":"sess-args2","total_cost_usd":0.0,"usage":{"input_tokens":1,"output_tokens":1}}'
                done
                """.formatted(argsDump.toString()));

        worker = new ClaudeCliWorker(argStub.toString(), null, null, false, null, Duration.ofSeconds(10));
        // setAllowedTools 미호출 — 노출 제한 없음
        worker.start();
        worker.turnFirst(List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "go")));

        String args = Files.readString(argsDump);
        assertThat(args).doesNotContain("--allowedTools");
    }

    @Test
    void cr117subagent_disallowSubagent_injects_disallowedTools_Agent_flag() throws IOException {
        Path argsDump = tempDir.resolve("args-sa-" + System.nanoTime() + ".txt");
        Path argStub = writeStub("""
                #!/bin/bash
                echo "$@" > '%s'
                echo '{"type":"system","subtype":"init","session_id":"sess-sa"}'
                while IFS= read -r line; do
                  if [ -z "$line" ]; then continue; fi
                  echo '{"type":"result","subtype":"success","result":"ok","session_id":"sess-sa","total_cost_usd":0.0,"usage":{"input_tokens":1,"output_tokens":1}}'
                done
                """.formatted(argsDump.toString()));

        worker = new ClaudeCliWorker(argStub.toString(), null, null, false, null, Duration.ofSeconds(10));
        worker.setDisallowSubagent(true); // CR-117: subagent OFF
        worker.start();
        worker.turnFirst(List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "go")));

        String args = Files.readString(argsDump);
        assertThat(args).contains("--disallowedTools Agent");
    }

    @Test
    void cr117subagent_default_does_not_inject_disallowedTools() throws IOException {
        Path argsDump = tempDir.resolve("args-sa2-" + System.nanoTime() + ".txt");
        Path argStub = writeStub("""
                #!/bin/bash
                echo "$@" > '%s'
                echo '{"type":"system","subtype":"init","session_id":"sess-sa2"}'
                while IFS= read -r line; do
                  if [ -z "$line" ]; then continue; fi
                  echo '{"type":"result","subtype":"success","result":"ok","session_id":"sess-sa2","total_cost_usd":0.0,"usage":{"input_tokens":1,"output_tokens":1}}'
                done
                """.formatted(argsDump.toString()));

        worker = new ClaudeCliWorker(argStub.toString(), null, null, false, null, Duration.ofSeconds(10));
        // setDisallowSubagent 미호출 — 기본 ON(현행: subagent 허용)
        worker.start();
        worker.turnFirst(List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "go")));

        String args = Files.readString(argsDump);
        assertThat(args).doesNotContain("--disallowedTools");
    }

    @Test
    void cr112_result_is_error_true_with_success_subtype_throws() throws IOException {
        // 사용자가 지목한 진범: CLI 가 socket closed / API 에러를 subtype=success 라도
        // is_error:true 인 result 이벤트로 둔갑시켜 발행 → COMPLETED 오판 방지를 위해 예외 승격.
        Path errStub = writeStub("""
                #!/bin/bash
                echo '{"type":"system","subtype":"init","session_id":"sess-err1"}'
                while IFS= read -r line; do
                  if [ -z "$line" ]; then continue; fi
                  echo '{"type":"result","subtype":"success","is_error":true,"result":"API Error: socket connection was closed","session_id":"sess-err1","total_cost_usd":0.0,"usage":{"input_tokens":1,"output_tokens":1}}'
                done
                """);
        worker = new ClaudeCliWorker(errStub.toString(), null, null, false, null, Duration.ofSeconds(10));
        worker.start();

        assertThatThrownBy(() -> worker.turnFirst(List.of(
                UnifiedMessage.ofText(UnifiedMessage.Role.USER, "hi"))))
                .isInstanceOf(ClaudeCliException.class)
                .hasMessageContaining("is_error=true")
                .hasMessageContaining("socket connection was closed");
    }

    @Test
    void cr112_result_is_error_true_with_error_subtype_throws() throws IOException {
        // error_during_execution 등 error_* subtype 도 is_error:true → 동일하게 예외 승격.
        Path errStub = writeStub("""
                #!/bin/bash
                echo '{"type":"system","subtype":"init","session_id":"sess-err2"}'
                while IFS= read -r line; do
                  if [ -z "$line" ]; then continue; fi
                  echo '{"type":"result","subtype":"error_during_execution","is_error":true,"session_id":"sess-err2","total_cost_usd":0.0,"usage":{"input_tokens":1,"output_tokens":1}}'
                done
                """);
        worker = new ClaudeCliWorker(errStub.toString(), null, null, false, null, Duration.ofSeconds(10));
        worker.start();

        assertThatThrownBy(() -> worker.turnFirst(List.of(
                UnifiedMessage.ofText(UnifiedMessage.Role.USER, "hi"))))
                .isInstanceOf(ClaudeCliException.class)
                .hasMessageContaining("error_during_execution");
    }

    @Test
    void cr112_result_is_error_false_returns_normally() throws IOException {
        // 정상 success(is_error:false) 는 오탐 없이 그대로 반환 — 회귀 방지.
        Path okStub = writeStub("""
                #!/bin/bash
                echo '{"type":"system","subtype":"init","session_id":"sess-ok"}'
                while IFS= read -r line; do
                  if [ -z "$line" ]; then continue; fi
                  echo '{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"done"}]}}'
                  echo '{"type":"result","subtype":"success","is_error":false,"result":"done","session_id":"sess-ok","total_cost_usd":0.0,"usage":{"input_tokens":1,"output_tokens":1}}'
                done
                """);
        worker = new ClaudeCliWorker(okStub.toString(), null, null, false, null, Duration.ofSeconds(10));
        worker.start();

        LLMResponse resp = worker.turnFirst(List.of(
                UnifiedMessage.ofText(UnifiedMessage.Role.USER, "hi")));
        assertThat(resp.textContent()).isEqualTo("done");
        assertThat(resp.finishReason()).isEqualTo(LLMResponse.FinishReason.END);
    }

    @Test
    void cr117_workingDirectory_injects_workspace_header_into_aimbase_server_mcp_config() throws IOException {
        Path argsDump = tempDir.resolve("args-ws-" + System.nanoTime() + ".txt");
        Path argStub = writeStub("""
                #!/bin/bash
                echo "$@" > '%s'
                echo '{"type":"system","subtype":"init","session_id":"sess-ws"}'
                while IFS= read -r line; do
                  if [ -z "$line" ]; then continue; fi
                  echo '{"type":"result","subtype":"success","result":"ok","session_id":"sess-ws","total_cost_usd":0.0,"usage":{"input_tokens":1,"output_tokens":1}}'
                done
                """.formatted(argsDump.toString()));

        // aimbase-server 항목이 있는 MCP config (CR-072 서버 노출 형태)
        String mcpConfig = """
                {"mcpServers":{"aimbase-server":{"type":"sse","url":"http://h/mcp/sse",\
                "headers":{"X-API-Key":"k"}}}}""";
        worker = new ClaudeCliWorker(argStub.toString(), null, null, false, null,
                Duration.ofSeconds(10), mcpConfig);
        // run 격리 작업장 (실존 디렉토리)
        Path runWs = Files.createDirectory(tempDir.resolve("runs-" + System.nanoTime()));
        worker.setWorkingDirectory(runWs.toString());
        worker.start();
        worker.turnFirst(List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "go")));

        String args = Files.readString(argsDump);
        // --mcp-config JSON 에 workspace 헤더가 run 작업장 경로로 주입되어야 한다.
        assertThat(args).contains("X-Aimbase-Workspace-Path");
        assertThat(args).contains(runWs.toString());
        // 기존 헤더(X-API-Key)는 보존
        assertThat(args).contains("X-API-Key");
    }

    @Test
    void cr117_no_workingDirectory_leaves_mcp_config_untouched() throws IOException {
        Path argsDump = tempDir.resolve("args-nows-" + System.nanoTime() + ".txt");
        Path argStub = writeStub("""
                #!/bin/bash
                echo "$@" > '%s'
                echo '{"type":"system","subtype":"init","session_id":"sess-nows"}'
                while IFS= read -r line; do
                  if [ -z "$line" ]; then continue; fi
                  echo '{"type":"result","subtype":"success","result":"ok","session_id":"sess-nows","total_cost_usd":0.0,"usage":{"input_tokens":1,"output_tokens":1}}'
                done
                """.formatted(argsDump.toString()));

        String mcpConfig = """
                {"mcpServers":{"aimbase-server":{"type":"sse","url":"http://h/mcp/sse"}}}""";
        worker = new ClaudeCliWorker(argStub.toString(), null, null, false, null,
                Duration.ofSeconds(10), mcpConfig);
        // setWorkingDirectory 미호출 → 헤더 주입 없이 기존 동작 보존
        worker.start();
        worker.turnFirst(List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "go")));

        String args = Files.readString(argsDump);
        assertThat(args).doesNotContain("X-Aimbase-Workspace-Path");
    }

    @Test
    void cr117_workingDirectory_without_aimbase_server_leaves_config_untouched() throws IOException {
        Path argsDump = tempDir.resolve("args-local-" + System.nanoTime() + ".txt");
        Path argStub = writeStub("""
                #!/bin/bash
                echo "$@" > '%s'
                echo '{"type":"system","subtype":"init","session_id":"sess-local"}'
                while IFS= read -r line; do
                  if [ -z "$line" ]; then continue; fi
                  echo '{"type":"result","subtype":"success","result":"ok","session_id":"sess-local","total_cost_usd":0.0,"usage":{"input_tokens":1,"output_tokens":1}}'
                done
                """.formatted(argsDump.toString()));

        // 서버 MCP 미노출 (stdio-only 'aimbase' 키만) → 헤더 주입 대상 없음
        String mcpConfig = """
                {"mcpServers":{"aimbase":{"command":"java","args":["-jar","x.jar","--mcp-stdio"]}}}""";
        worker = new ClaudeCliWorker(argStub.toString(), null, null, false, null,
                Duration.ofSeconds(10), mcpConfig);
        Path runWs = Files.createDirectory(tempDir.resolve("runs-local-" + System.nanoTime()));
        worker.setWorkingDirectory(runWs.toString());
        worker.start();
        worker.turnFirst(List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "go")));

        String args = Files.readString(argsDump);
        // aimbase-server 항목이 없으므로 헤더 미주입 (기존 동작 보존)
        assertThat(args).doesNotContain("X-Aimbase-Workspace-Path");
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
