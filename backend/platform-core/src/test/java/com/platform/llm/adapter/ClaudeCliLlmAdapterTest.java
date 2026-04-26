package com.platform.llm.adapter;

import com.platform.llm.claudecli.ClaudeCliBranchScope;
import com.platform.llm.claudecli.ClaudeCliException;
import com.platform.llm.claudecli.ClaudeCliWorker;
import com.platform.llm.claudecli.ClaudeCliWorkerPool;
import com.platform.llm.model.LLMRequest;
import com.platform.llm.model.LLMResponse;
import com.platform.llm.model.LLMStreamChunk;
import com.platform.llm.model.ModelConfig;
import com.platform.llm.model.ToolCall;
import com.platform.llm.model.UnifiedMessage;
import com.platform.tool.model.UnifiedToolDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClaudeCliLlmAdapterTest {

    @TempDir Path tempDir;

    private Path binary;
    private Path inputLog;
    private ClaudeCliWorkerPool pool;

    @BeforeEach
    void setup() throws IOException {
        // 스텁은 받은 stdin 라인을 파일에 기록한 후, 각 라인마다 result 이벤트를 1개 방출.
        // 워커는 첫 result 에서 턴을 종료하므로, 실제로 몇 줄이 stdin 에 주입되었는지는
        // inputLog 파일을 확인해야 한다.
        Path inputLog = tempDir.resolve("stdin.log");
        binary = writeStub("""
                #!/bin/bash
                echo '{"type":"system","subtype":"init","session_id":"sess-A"}'
                while IFS= read -r line; do
                  if [ -z "$line" ]; then continue; fi
                  echo "$line" >> %s
                  echo '{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"ok"}]}}'
                  echo '{"type":"result","subtype":"success","result":"ok","session_id":"sess-A","total_cost_usd":0.0,"usage":{"input_tokens":1,"output_tokens":1}}'
                done
                """.formatted(inputLog));
        this.inputLog = inputLog;
        pool = new ClaudeCliWorkerPool(
                (model, sid, fork, cfg) -> new ClaudeCliWorker(
                        binary.toString(), model, sid, fork, cfg, Duration.ofSeconds(5)),
                5, Duration.ofSeconds(5));
    }

    @Test
    void chat_requires_session_id() {
        ClaudeCliLlmAdapter adapter = new ClaudeCliLlmAdapter(pool, null, null);
        LLMRequest req = new LLMRequest("claude-sonnet-4-6",
                List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "hi")));
        assertThatThrownBy(() -> adapter.chat(req).join())
                .hasCauseInstanceOf(ClaudeCliException.class);
    }

    @Test
    void first_turn_sends_all_messages_subsequent_sends_last_only() throws Exception {
        ClaudeCliLlmAdapter adapter = new ClaudeCliLlmAdapter(pool, null, null);

        List<UnifiedMessage> history = List.of(
                UnifiedMessage.ofText(UnifiedMessage.Role.USER, "u1"),
                UnifiedMessage.ofText(UnifiedMessage.Role.USER, "u2"),
                UnifiedMessage.ofText(UnifiedMessage.Role.USER, "u3"));
        LLMRequest first = new LLMRequest(
                "claude-sonnet-4-6", history, null, ModelConfig.defaults(), false, "run-99");

        LLMResponse firstResp = adapter.chat(first).get();
        assertThat(firstResp.textContent()).isEqualTo("ok");

        LLMRequest second = new LLMRequest(
                "claude-sonnet-4-6",
                List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "u4")),
                null, ModelConfig.defaults(), false, "run-99");
        LLMResponse secondResp = adapter.chat(second).get();
        assertThat(secondResp.textContent()).isEqualTo("ok");

        // 핵심 검증: stdin.log 내용 — 첫 턴은 u1/u2/u3 3줄, 이후 턴은 u4 1줄
        // 총 4줄이며 순서대로 u1, u2, u3, u4 를 포함해야 한다.
        java.util.List<String> lines = Files.readAllLines(inputLog);
        assertThat(lines).hasSize(4);
        assertThat(lines.get(0)).contains("\"content\":\"u1\"");
        assertThat(lines.get(1)).contains("\"content\":\"u2\"");
        assertThat(lines.get(2)).contains("\"content\":\"u3\"");
        assertThat(lines.get(3)).contains("\"content\":\"u4\"");

        pool.shutdownForRun("run-99");
    }

    @Test
    void chatStream_emits_deltas_and_done() {
        ClaudeCliLlmAdapter adapter = new ClaudeCliLlmAdapter(pool, null, null);
        LLMRequest req = new LLMRequest(
                "claude-sonnet-4-6",
                List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "hi")),
                null, ModelConfig.defaults(), true, "run-stream");

        List<LLMStreamChunk> chunks = new ArrayList<>();
        adapter.chatStream(req, chunks::add);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(chunks.size() - 1).done()).isTrue();
        assertThat(chunks.stream().filter(c -> !c.done()).map(LLMStreamChunk::delta))
                .contains("ok");

        pool.shutdownForRun("run-stream");
    }

    @Test
    void transformToolDefs_returns_null_ignoring_tools() {
        ClaudeCliLlmAdapter adapter = new ClaudeCliLlmAdapter(pool, null, null);
        assertThat(adapter.transformToolDefs(List.of())).isNull();
    }

    @Test
    void getProvider_is_anthropic_cli() {
        ClaudeCliLlmAdapter adapter = new ClaudeCliLlmAdapter(pool, null, null);
        assertThat(adapter.getProvider()).isEqualTo("anthropic-cli");
    }

    @Test
    void branch_scope_spawns_fork_worker_and_reuses_within_scope() throws Exception {
        ClaudeCliLlmAdapter adapter = new ClaudeCliLlmAdapter(pool, null, null);
        String runId = "run-branch";

        // 선행 메인 턴: session_id 확보
        adapter.chat(new LLMRequest("m", List.of(
                UnifiedMessage.ofText(UnifiedMessage.Role.USER, "main1")),
                null, ModelConfig.defaults(), false, runId)).get();

        assertThat(pool.activeWorkerCount(runId)).isEqualTo(1); // main 1

        // 브랜치 스코프 내 2회 호출 → fork 워커 1개만 spawn 되고 재사용되어야 함
        try (ClaudeCliBranchScope ignored = ClaudeCliBranchScope.open(runId, "branch-A")) {
            adapter.chat(new LLMRequest("m", List.of(
                    UnifiedMessage.ofText(UnifiedMessage.Role.USER, "A1")),
                    null, ModelConfig.defaults(), false, runId)).get();
            assertThat(pool.activeWorkerCount(runId)).isEqualTo(2); // main + fork-A

            adapter.chat(new LLMRequest("m", List.of(
                    UnifiedMessage.ofText(UnifiedMessage.Role.USER, "A2")),
                    null, ModelConfig.defaults(), false, runId)).get();
            assertThat(pool.activeWorkerCount(runId)).isEqualTo(2); // 재사용, 증가 없음
        }

        // closeBranch 를 Adapter 호출 — ParallelStepExecutor 가 릴리즈했다고 가정
        adapter.closeBranch(runId, "branch-A");
        // 최대 2초 대기 후 fork 워커 종료
        long deadline = System.currentTimeMillis() + 2000;
        while (pool.activeWorkerCount(runId) > 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(pool.activeWorkerCount(runId)).isEqualTo(1);

        pool.shutdownForRun(runId);
    }

    @Test
    void different_branches_get_different_fork_workers() throws Exception {
        ClaudeCliLlmAdapter adapter = new ClaudeCliLlmAdapter(pool, null, null);
        String runId = "run-multibranch";

        adapter.chat(new LLMRequest("m", List.of(
                UnifiedMessage.ofText(UnifiedMessage.Role.USER, "m")),
                null, ModelConfig.defaults(), false, runId)).get();

        try (ClaudeCliBranchScope bA = ClaudeCliBranchScope.open(runId, "A")) {
            adapter.chat(new LLMRequest("m", List.of(
                    UnifiedMessage.ofText(UnifiedMessage.Role.USER, "A")),
                    null, ModelConfig.defaults(), false, runId)).get();
        }
        assertThat(pool.activeWorkerCount(runId)).isEqualTo(2); // main + A (A release 전)
        adapter.closeBranch(runId, "A");

        try (ClaudeCliBranchScope bB = ClaudeCliBranchScope.open(runId, "B")) {
            adapter.chat(new LLMRequest("m", List.of(
                    UnifiedMessage.ofText(UnifiedMessage.Role.USER, "B")),
                    null, ModelConfig.defaults(), false, runId)).get();
            assertThat(pool.activeWorkerCount(runId)).isGreaterThanOrEqualTo(2); // main + B
        }
        adapter.closeBranch(runId, "B");

        pool.shutdownForRun(runId);
    }

    @Test
    void tool_use_blocks_are_observed_but_NOT_propagated_to_orchestrator_phase9() throws Exception {
        Path inputLog = tempDir.resolve("tools-stdin.log");
        Path toolBinary = writeStub("""
                #!/bin/bash
                echo '{"type":"system","subtype":"init","session_id":"sess-tool"}'
                while IFS= read -r line; do
                  if [ -z "$line" ]; then continue; fi
                  echo "$line" >> %s
                  # assistant 이벤트 안에 tool_use 블록 포함
                  echo '{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"reading file"},{"type":"tool_use","id":"toolu_01","name":"Read","input":{"path":"a.txt"}}]}}'
                  echo '{"type":"result","subtype":"success","result":"reading file","session_id":"sess-tool","total_cost_usd":0.0,"usage":{"input_tokens":1,"output_tokens":1}}'
                done
                """.formatted(inputLog));

        ClaudeCliWorkerPool toolPool = new ClaudeCliWorkerPool(
                (model, sid, fork, cfg) -> new ClaudeCliWorker(
                        toolBinary.toString(), model, sid, fork, cfg, Duration.ofSeconds(5)),
                5, Duration.ofSeconds(2));

        ClaudeCliLlmAdapter adapter = new ClaudeCliLlmAdapter(toolPool, null, null);

        UnifiedToolDef readTool = new UnifiedToolDef(
                "Read",
                "Read a file",
                Map.of("type", "object",
                       "properties", Map.of("path", Map.of("type", "string")),
                       "required", List.of("path")));

        LLMRequest req = new LLMRequest(
                "claude-sonnet-4-6",
                List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "read a.txt")),
                List.of(readTool),
                ModelConfig.defaults(), false, "run-tool");

        LLMResponse resp = adapter.chat(req).get();

        // Phase 9: CLI 가 도구 루프를 자체적으로 완결하므로 OrchestratorEngine 입장에서는
        // 도구 호출이 없는 응답으로 보여야 한다 (그렇지 않으면 외부 루프와 CLI 루프가 충돌).
        // 어댑터는 tool_use 를 관찰 로그로만 남기고 LLMResponse 에 노출하지 않는다.
        assertThat(resp.toolCalls()).isEmpty();
        assertThat(resp.finishReason()).isEqualTo(LLMResponse.FinishReason.END);
        assertThat(adapter.parseToolCalls(resp)).isEmpty();

        // 도구는 MCP 채널로 전달되므로 stdin 에 도구 명세 텍스트가 들어가지 않는다.
        java.util.List<String> lines = Files.readAllLines(inputLog);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).doesNotContain("<tools>");
        assertThat(lines.get(0)).contains("\"content\":\"read a.txt\"");

        toolPool.shutdownForRun("run-tool");
    }

    @Test
    void tool_result_is_forwarded_to_cli_as_user_message() throws Exception {
        Path inputLog = tempDir.resolve("toolresult-stdin.log");
        Path toolBinary = writeStub("""
                #!/bin/bash
                echo '{"type":"system","subtype":"init","session_id":"sess-tr"}'
                while IFS= read -r line; do
                  if [ -z "$line" ]; then continue; fi
                  echo "$line" >> %s
                  echo '{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"done"}]}}'
                  echo '{"type":"result","subtype":"success","result":"done","session_id":"sess-tr","total_cost_usd":0.0,"usage":{"input_tokens":1,"output_tokens":1}}'
                done
                """.formatted(inputLog));

        ClaudeCliWorkerPool toolPool = new ClaudeCliWorkerPool(
                (model, sid, fork, cfg) -> new ClaudeCliWorker(
                        toolBinary.toString(), model, sid, fork, cfg, Duration.ofSeconds(5)),
                5, Duration.ofSeconds(2));

        ClaudeCliLlmAdapter adapter = new ClaudeCliLlmAdapter(toolPool, null, null);

        // 첫 턴: 평범한 USER
        adapter.chat(new LLMRequest("m",
                List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "hi")),
                null, ModelConfig.defaults(), false, "run-tr")).get();

        // 이후 턴: TOOL_RESULT 메시지를 OrchestratorEngine 이 주입한다고 가정
        UnifiedMessage trMsg = UnifiedMessage.ofToolResults(List.of(
                new com.platform.llm.model.ContentBlock.ToolResult("toolu_01", "file contents")));
        adapter.chat(new LLMRequest("m",
                List.of(trMsg), null, ModelConfig.defaults(), false, "run-tr")).get();

        java.util.List<String> lines = Files.readAllLines(inputLog);
        assertThat(lines).hasSize(2);
        // 두 번째 라인이 tool_result 블록을 포함한 user 메시지여야 함
        assertThat(lines.get(1)).contains("\"type\":\"tool_result\"");
        assertThat(lines.get(1)).contains("\"tool_use_id\":\"toolu_01\"");
        assertThat(lines.get(1)).contains("file contents");

        toolPool.shutdownForRun("run-tr");
    }

    @Test
    void transformToolDefs_always_returns_null_phase9() {
        // Phase 9: 도구는 MCP 채널 (--mcp-config) 로 전달되므로 어댑터가 도구 정의를 변환하지 않는다.
        ClaudeCliLlmAdapter adapter = new ClaudeCliLlmAdapter(pool, null, null);
        assertThat(adapter.transformToolDefs(List.of(
                new UnifiedToolDef("Read", "Read a file",
                        Map.of("type", "object", "properties", Map.of()))))).isNull();
        assertThat(adapter.transformToolDefs(null)).isNull();
        assertThat(adapter.transformToolDefs(List.of())).isNull();
    }

    @Test
    void stripMcpPrefix_removes_mcp_server_prefix_phase9() {
        // CLI 가 MCP 도구 호출 시 발행하는 mcp__<server>__<tool> 이름을 OrchestratorEngine 의
        // 원본 도구명(<tool>) 으로 매핑한다.
        assertThat(com.platform.llm.claudecli.ClaudeCliWorker.stripMcpPrefix("mcp__aimbase__bash"))
                .isEqualTo("bash");
        assertThat(com.platform.llm.claudecli.ClaudeCliWorker.stripMcpPrefix("mcp__aimbase__file_read"))
                .isEqualTo("file_read");
        // 비-MCP 이름은 그대로
        assertThat(com.platform.llm.claudecli.ClaudeCliWorker.stripMcpPrefix("bash"))
                .isEqualTo("bash");
        assertThat(com.platform.llm.claudecli.ClaudeCliWorker.stripMcpPrefix(null)).isNull();
    }

    @Test
    void branch_scope_with_foreign_run_id_falls_back_to_main() throws Exception {
        // BranchScope.parentRunId != request.sessionId 인 경우 → main 경로 유지
        ClaudeCliLlmAdapter adapter = new ClaudeCliLlmAdapter(pool, null, null);

        try (ClaudeCliBranchScope ignored = ClaudeCliBranchScope.open("other-run", "X")) {
            adapter.chat(new LLMRequest("m", List.of(
                    UnifiedMessage.ofText(UnifiedMessage.Role.USER, "hi")),
                    null, ModelConfig.defaults(), false, "run-me")).get();
        }
        // run-me 에는 main 1개만 있어야 함
        assertThat(pool.activeWorkerCount("run-me")).isEqualTo(1);
        assertThat(pool.activeWorkerCount("other-run")).isZero();

        pool.shutdownForRun("run-me");
    }

    private Path writeStub(String body) throws IOException {
        Path p = tempDir.resolve("claude-stub-" + System.nanoTime() + ".sh");
        Files.writeString(p, body);
        try {
            Files.setPosixFilePermissions(p, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException ignored) {}
        return p;
    }
}
