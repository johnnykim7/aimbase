package com.platform.runner.claudecli;

import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.LLMResponse;
import com.platform.llm.model.UnifiedMessage;
import com.platform.skill.SkillSetHeader;
import com.platform.tool.SkillRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

    /**
     * 스트리밍 경로에서 2턴째 이후 <b>이전 턴 사용자 질문이 재전송되지 않는지</b> 검증.
     *
     * <p>회귀 대상: 워커가 {@code first} 플래그를 상태 검증에만 쓰고 받은 messages 를 전부 stdin 에
     * 쓰던 시절, 위젯(스트리밍) 대화에서 과거 질문이 매 턴 누적 전송됐다. CLI 는 이미 답한 질문을
     * 다시 받아 앞선 지시부터 재수행하고 정작 이번 질문은 묻혔다(운영 세션 sess-1785039270248-pyhw08hb:
     * "/tmp 가 어디냐" 에 엑셀·PPT 재생성으로 응답).
     */
    @Test
    void stream_turn_does_not_resend_previous_user_messages() throws IOException {
        Path stdinDump = tempDir.resolve("stdin-" + System.nanoTime() + ".txt");
        Path dumpStub = writeStub("""
                #!/bin/bash
                echo '{"type":"system","subtype":"init","session_id":"sess-dump"}'
                while IFS= read -r line; do
                  if [ -z "$line" ]; then continue; fi
                  echo "$line" >> '%s'
                  echo '{"type":"result","subtype":"success","result":"ok","session_id":"sess-dump","total_cost_usd":0.0,"usage":{"input_tokens":1,"output_tokens":1}}'
                done
                """.formatted(stdinDump.toString()));

        worker = new ClaudeCliWorker(dumpStub.toString(), null, null, false, null, Duration.ofSeconds(10));
        worker.start();

        // 1턴째 — 전체 주입 (CLI 맥락 초기화)
        worker.turnStream(List.of(
                UnifiedMessage.ofText(UnifiedMessage.Role.USER, "turn-1")), true, d -> {});

        // 2턴째 — 호출자가 누적 이력 전체를 넘겨도 이번 턴 질문만 나가야 한다
        worker.turnStream(List.of(
                UnifiedMessage.ofText(UnifiedMessage.Role.USER, "turn-1"),
                UnifiedMessage.ofText(UnifiedMessage.Role.USER, "turn-2")), false, d -> {});

        List<String> lines = Files.readAllLines(stdinDump);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).contains("turn-1");
        // 핵심: 2턴째 라인에 이전 턴 질문이 섞이면 안 된다
        assertThat(lines.get(1)).contains("turn-2");
        assertThat(lines.get(1)).doesNotContain("turn-1");
    }

    /**
     * 도구 루프 중 재주입되는 TOOL_RESULT 는 이번 턴 입력이므로 절단되면 안 된다.
     *
     * <p>stdin 덤프 대신 절단 함수를 직접 검증한다 — 스텁이 첫 줄을 읽자마자 {@code result} 를 내보내
     * turn 이 반환되므로, 두 번째 줄의 기록 시점은 스텁 프로세스와 경쟁 상태가 된다(무엇을 <b>보냈는지</b>가
     * 검증 대상이지 스텁이 언제 받아쓰는지가 아니다).
     */
    @Test
    void trim_keeps_current_turn_user_and_tool_results_only() {
        List<UnifiedMessage> input = List.of(
                UnifiedMessage.ofText(UnifiedMessage.Role.USER, "old-question"),
                UnifiedMessage.ofText(UnifiedMessage.Role.ASSISTANT, "old-answer"),
                UnifiedMessage.ofText(UnifiedMessage.Role.USER, "current-question"),
                UnifiedMessage.ofToolResults(List.of(
                        new ContentBlock.ToolResult("tu-1", "tool-output-kept"))));

        List<UnifiedMessage> trimmed = ClaudeCliWorker.trimToCurrentTurn(input);

        assertThat(trimmed).hasSize(2);
        assertThat(trimmed.get(0).role()).isEqualTo(UnifiedMessage.Role.USER);
        assertThat(flatten(trimmed.get(0))).isEqualTo("current-question");
        assertThat(trimmed.get(1).role()).isEqualTo(UnifiedMessage.Role.TOOL_RESULT);
    }

    /** USER 없이 TOOL_RESULT 만 재주입되는 도구 루프 중간 턴은 전량 보존한다. */
    @Test
    void trim_preserves_all_when_no_user_message() {
        List<UnifiedMessage> input = List.of(
                UnifiedMessage.ofToolResults(List.of(
                        new ContentBlock.ToolResult("tu-1", "only-tool-result"))));

        assertThat(ClaudeCliWorker.trimToCurrentTurn(input)).isEqualTo(input);
    }

    private static String flatten(UnifiedMessage msg) {
        return msg.content().stream()
                .filter(b -> b instanceof ContentBlock.Text)
                .map(b -> ((ContentBlock.Text) b).text())
                .reduce("", String::concat);
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
        // CR-126 이후 AIMBASE 봉인이 --disallowedTools 로 built-in 을 차단하므로 플래그 자체는 존재한다.
        // 이 테스트의 관심사는 "Agent(서브에이전트) 가 차단되지 않는가" 이므로 그것만 검증한다.
        assertThat(args).doesNotContain("--disallowedTools Agent");
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

    // CR-147: 단계가 고정한 스킬 판을 서버로 돌려주는 머리글과 NATIVE 의 스킬 전용 통로(BIZ-123). TC-SKB-024.

    private static final List<SkillRef> PINS = List.of(new SkillRef("ax-proposal", "tenant", 1));
    private static final String SERVER_MCP = """
            {"mcpServers":{"aimbase-server":{"type":"http","url":"http://h:8181/mcp",\
            "headers":{"X-API-Key":"k"}}}}""";

    private Path argDumpStub(Path argsDump) throws IOException {
        return writeStub("""
                #!/bin/bash
                printf '%%s\\n' "$@" > '%s'
                echo '{"type":"system","subtype":"init","session_id":"sess-sk"}'
                while IFS= read -r line; do
                  if [ -z "$line" ]; then continue; fi
                  echo '{"type":"result","subtype":"success","result":"ok","session_id":"sess-sk","total_cost_usd":0.0,"usage":{"input_tokens":1,"output_tokens":1}}'
                done
                """.formatted(argsDump.toString()));
    }

    private List<String> runAndDumpArgs(ClaudeCliCommandBuilder.ToolMode mode, List<SkillRef> skills,
                                        List<String> allowedTools, String mcpConfig) throws IOException {
        Path argsDump = tempDir.resolve("args-sk-" + System.nanoTime() + ".txt");
        worker = new ClaudeCliWorker(argDumpStub(argsDump).toString(), null, null, false, null,
                Duration.ofSeconds(10), mcpConfig);
        worker.setToolMode(mode);
        if (allowedTools != null) worker.setAllowedTools(allowedTools);
        worker.setSkillSet(skills);
        worker.start();
        worker.turnFirst(List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "go")));
        return Files.readAllLines(argsDump);
    }

    private static String argAfter(List<String> args, String flag) {
        return args.get(args.indexOf(flag) + 1);
    }

    @Test
    void cr147_aimbase_mode_sends_pins_in_the_server_header() throws IOException {
        List<String> args = runAndDumpArgs(ClaudeCliCommandBuilder.ToolMode.AIMBASE, PINS, null, SERVER_MCP);

        String mcp = argAfter(args, "--mcp-config");
        assertThat(mcp).contains("\"aimbase-server\"", "\"X-API-Key\"",
                "\"" + SkillSetHeader.NAME + "\":\"" + SkillSetHeader.encode(PINS) + "\"");
        assertThat(mcp).doesNotContain("aimbase-skills");
    }

    @Test
    void cr147_native_mode_with_pins_gets_only_the_skill_route() throws IOException {
        List<String> args = runAndDumpArgs(ClaudeCliCommandBuilder.ToolMode.NATIVE, PINS,
                List.of("skill_open", "skill_read", "get_opportunity"), SERVER_MCP);

        String mcp = argAfter(args, "--mcp-config");
        assertThat(mcp).contains("\"aimbase-skills\"", "http://h:8181/mcp-skills", "\"X-API-Key\"",
                SkillSetHeader.NAME);
        assertThat(mcp).doesNotContain("aimbase-server", "http://h:8181/mcp\"");
        assertThat(args).contains("mcp__aimbase-skills__skill_open", "mcp__aimbase-skills__skill_read");
        assertThat(args).doesNotContain("mcp__aimbase-skills__get_opportunity");
    }

    @Test
    void cr147_native_mode_without_pins_keeps_the_empty_config() throws IOException {
        List<String> args = runAndDumpArgs(ClaudeCliCommandBuilder.ToolMode.NATIVE, null, null, SERVER_MCP);

        assertThat(argAfter(args, "--mcp-config")).isEqualTo("{\"mcpServers\":{}}");
    }

    @Test
    void cr147_pins_without_a_server_entry_fail_at_start() throws IOException {
        for (ClaudeCliCommandBuilder.ToolMode mode : ClaudeCliCommandBuilder.ToolMode.values()) {
            ClaudeCliWorker w = new ClaudeCliWorker(binary.toString(), null, null, false, null,
                    Duration.ofSeconds(10), "{\"mcpServers\":{\"aimbase\":{\"command\":\"java\"}}}");
            w.setToolMode(mode);
            w.setSkillSet(PINS);
            assertThatThrownBy(w::start).as(mode.name())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("BIZ-123");
            assertThat(w.isAlive()).isFalse();
        }
    }

    @Test
    void cr147_skill_set_cannot_change_after_start() throws IOException {
        worker = new ClaudeCliWorker(binary.toString(), null, null, false, null, Duration.ofSeconds(10), SERVER_MCP);
        worker.start();
        assertThatThrownBy(() -> worker.setSkillSet(PINS)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cr147_skills_url_sits_next_to_the_server_route() {
        assertThat(ClaudeCliWorker.skillsUrl("http://h:8181/mcp")).isEqualTo("http://h:8181/mcp-skills");
        assertThat(ClaudeCliWorker.skillsUrl("http://h:8181/mcp/")).isEqualTo("http://h:8181/mcp-skills");
        assertThat(ClaudeCliWorker.skillsUrl("https://a.example.com/base/mcp")).isEqualTo("https://a.example.com/base/mcp-skills");
        assertThat(ClaudeCliWorker.skillsUrl("http://h/mcp/sse")).isEqualTo("http://h/mcp-skills");
    }

    // CR-152: CLI 는 기본값으로 MCP 연결을 기다리지 않고 첫 턴을 시작한다. 스텁은 실제 CLI 처럼
    // 입력을 받은 뒤 턴마다 init 을 내고, 기동 때 받은 환경변수를 파일에 남긴다.

    private Path initStub(String mcpServers, Path envDump) throws IOException {
        return writeStub("""
                #!/bin/bash
                echo "${MCP_CONNECTION_NONBLOCKING:-unset} ${MCP_CONNECT_TIMEOUT_MS:-unset}" > '%s'
                while IFS= read -r line; do
                  if [ -z "$line" ]; then continue; fi
                  echo '{"type":"system","subtype":"init","session_id":"sess-mcp","mcp_servers":%s}'
                  echo '{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"done"}]}}'
                  echo '{"type":"result","subtype":"success","result":"done","session_id":"sess-mcp","total_cost_usd":0.0,"usage":{"input_tokens":1,"output_tokens":1}}'
                done
                """.formatted(envDump.toString(), mcpServers));
    }

    private ClaudeCliWorker startWithInit(String mcpServers, String mcpConfig, Path envDump) throws IOException {
        worker = new ClaudeCliWorker(initStub(mcpServers, envDump).toString(), null, null, false, null,
                Duration.ofSeconds(10), mcpConfig);
        worker.setMcpConnectTimeout(Duration.ofSeconds(15));
        worker.start();
        return worker;
    }

    @Test
    @DisplayName("IT-01-003 1 연결중이면_턴이_실패한다")
    void cr152_turn_fails_when_the_server_is_still_pending() throws IOException {
        Path env = tempDir.resolve("env-" + System.nanoTime() + ".txt");
        startWithInit("[{\"name\":\"aimbase-server\",\"status\":\"pending\"}]", SERVER_MCP, env);

        assertThatThrownBy(() -> worker.turnFirst(List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "go"))))
                .isInstanceOf(ClaudeCliException.class)
                .hasMessageContaining("aimbase-server=pending");
    }

    @Test
    @DisplayName("IT-01-003 2 목록에_없으면_턴이_실패한다")
    void cr152_turn_fails_when_the_server_is_not_listed() throws IOException {
        Path env = tempDir.resolve("env-" + System.nanoTime() + ".txt");
        startWithInit("[]", SERVER_MCP, env);

        assertThatThrownBy(() -> worker.turnFirst(List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "go"))))
                .isInstanceOf(ClaudeCliException.class)
                .hasMessageContaining("aimbase-server=missing");
    }

    @Test
    @DisplayName("IT-01-002 1,2 연결되면_대기설정을_넘기고_답을_낸다")
    void cr152_turn_runs_when_the_server_is_connected() throws IOException {
        Path env = tempDir.resolve("env-" + System.nanoTime() + ".txt");
        startWithInit("[{\"name\":\"aimbase-server\",\"status\":\"connected\"}]", SERVER_MCP, env);

        LLMResponse resp = worker.turnFirst(List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "go")));

        assertThat(resp.textContent()).isEqualTo("done");
        assertThat(Files.readString(env).trim()).isEqualTo("false 15000");
    }

    @Test
    @DisplayName("IT-01-002 3 넘기는_서버가_없으면_CLI_값을_바꾸지_않는다")
    void cr152_without_mcp_servers_the_cli_keeps_its_defaults() throws IOException {
        Path env = tempDir.resolve("env-" + System.nanoTime() + ".txt");
        startWithInit("[]", null, env);

        LLMResponse resp = worker.turnFirst(List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "go")));

        assertThat(resp.textContent()).isEqualTo("done");
        // 워커는 손대지 않으므로 시험을 돌리는 프로세스의 값이 그대로 넘어간다.
        assertThat(Files.readString(env).trim()).isEqualTo(
                inherited(ClaudeCliWorker.ENV_MCP_NONBLOCKING) + " " + inherited(ClaudeCliWorker.ENV_MCP_CONNECT_TIMEOUT_MS));
    }

    private static String inherited(String name) {
        String v = System.getenv(name);
        return v == null || v.isEmpty() ? "unset" : v;
    }

    @Test
    @DisplayName("IT-01-003 3 NATIVE_스킬지정은_스킬통로만_본다")
    void cr152_native_mode_with_pins_waits_for_the_skill_route_only() throws IOException {
        Path env = tempDir.resolve("env-" + System.nanoTime() + ".txt");
        worker = new ClaudeCliWorker(initStub("[{\"name\":\"aimbase-skills\",\"status\":\"pending\"}]", env).toString(),
                null, null, false, null, Duration.ofSeconds(10), SERVER_MCP);
        worker.setToolMode(ClaudeCliCommandBuilder.ToolMode.NATIVE);
        worker.setSkillSet(PINS);
        worker.start();

        assertThatThrownBy(() -> worker.turnFirst(List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "go"))))
                .isInstanceOf(ClaudeCliException.class)
                .hasMessageContaining("aimbase-skills=pending")
                .hasMessageNotContaining("aimbase-server");
    }

    @Test
    @DisplayName("IT-01-010 7 시작_기록은_MCP_설정의_머리글과_환경변수_값을_가린다")
    void cr154_start_log_redacts_mcp_header_and_env_values() {
        String mcp = """
                {"mcpServers":{"aimbase-server":{"type":"http","url":"http://h:8181/mcp",
                "headers":{"Authorization":"ApiKey plat-secret-1","X-Aimbase-Agent-Id":"a1"}},
                "tool":{"command":"npx","env":{"TOKEN":"secret-2"}}}}""";

        List<String> logged = ClaudeCliWorker.redactMcpConfig(List.of("claude", "--mcp-config", mcp, "--model", "m"));

        assertThat(logged.get(2)).doesNotContain("plat-secret-1", "secret-2", "a1")
                .contains("http://h:8181/mcp", "[REDACTED]");
        assertThat(logged).containsSubsequence("claude", "--mcp-config").endsWith("--model", "m");
        assertThat(ClaudeCliWorker.redactMcpConfig(List.of("--mcp-config", "not json")))
                .containsExactly("--mcp-config", "[REDACTED]");
    }

    @Test
    void cr163_output_readers_do_not_take_virtual_thread_carriers() throws IOException {
        worker = new ClaudeCliWorker(
                binary.toString(), null, null, false, null, Duration.ofSeconds(10));
        worker.start();

        // getAllStackTraces 에는 가상 스레드가 나오지 않으므로, 두 읽기가 여기 보이면 플랫폼 스레드다.
        List<Thread> readers = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().equals("cli-worker-stdout-" + worker.pid())
                        || t.getName().equals("cli-worker-stderr-" + worker.pid()))
                .toList();

        assertThat(readers).hasSize(2).allMatch(t -> !t.isVirtual() && t.isDaemon());
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
