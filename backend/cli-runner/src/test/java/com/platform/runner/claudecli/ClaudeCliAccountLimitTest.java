package com.platform.runner.claudecli;

import com.platform.llm.model.LLMResponse;
import com.platform.llm.model.UnifiedMessage;
import org.junit.jupiter.api.AfterEach;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CR-161 TC-CG-118~120, 122: 계정 한도 거절 신호와 계정 폴더가 바뀐 run.
 *
 * <p>rate_limit_event 모양은 로컬 실행기 로그에서 본 실제 이벤트를 따른다.
 * {@code overageStatus=rejected} 는 평소에도 오므로 같이 싣는다.
 */
class ClaudeCliAccountLimitTest {

    private static final String INIT = "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"sess-limit\"}";
    private static final String ASSISTANT =
            "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"%s\"}]}}";
    private static final String RATE_LIMIT = "{\"type\":\"rate_limit_event\",\"rate_limit_info\":{\"status\":\"%s\","
            + "\"resetsAt\":1793082000,\"rateLimitType\":\"five_hour\",\"overageStatus\":\"rejected\","
            + "\"isUsingOverage\":false},\"session_id\":\"sess-limit\"}";
    private static final String RESULT_OK = "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,"
            + "\"result\":\"%s\",\"session_id\":\"sess-limit\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
    private static final String RESULT_ERROR = "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,"
            + "\"result\":\"limit reached\",\"session_id\":\"sess-limit\",\"usage\":{\"input_tokens\":0,\"output_tokens\":0}}";

    @TempDir
    Path tempDir;

    private ClaudeCliWorker worker;

    @AfterEach
    void teardown() {
        if (worker != null) worker.close();
    }

    @Test
    void tc_cg_118_rejectedThenErrorThrowsAccountLimitedAndEmitsNothing() throws IOException {
        worker = start(RATE_LIMIT.formatted("rejected"), ASSISTANT.formatted("limit reached"), RESULT_ERROR);
        List<String> deltas = new ArrayList<>();

        assertThatThrownBy(() -> worker.turnStream(user("hi"), true, deltas::add))
                .isInstanceOf(ClaudeCliException.class)
                .hasMessageStartingWith(ClaudeCliWorker.ACCOUNT_LIMITED_PREFIX + "resets_at=1793082000:type=five_hour:")
                .hasMessageContaining("is_error=true");
        assertThat(deltas).isEmpty();
    }

    @Test
    void tc_cg_119_rejectedThenSuccessEmitsHeldTextInOrder() throws IOException {
        worker = start(RATE_LIMIT.formatted("rejected"), ASSISTANT.formatted("one"), ASSISTANT.formatted("two"),
                RESULT_OK.formatted("onetwo"));
        List<String> deltas = new ArrayList<>();

        LLMResponse resp = worker.turnStream(user("hi"), true, deltas::add);

        assertThat(deltas).containsExactly("one", "two");
        assertThat(resp.textContent()).isEqualTo("onetwo");
        assertThat(resp.finishReason()).isEqualTo(LLMResponse.FinishReason.END);
    }

    @Test
    void tc_cg_120_allowedSignalKeepsStreamingAndErrorStaysPlain() throws IOException {
        worker = start(RATE_LIMIT.formatted("allowed"), ASSISTANT.formatted("hello"), RESULT_ERROR);
        List<String> deltas = new ArrayList<>();

        assertThatThrownBy(() -> worker.turnStream(user("hi"), true, deltas::add))
                .isInstanceOf(ClaudeCliException.class)
                .hasMessageStartingWith("CLI result reported is_error=true");
        assertThat(deltas).containsExactly("hello");
    }

    @Test
    void tc_cg_122_mainWorkerIsReplacedWhenAccountFolderChanges() throws IOException {
        Path binary = stub(ASSISTANT.formatted("ok"), RESULT_OK.formatted("ok"));
        ClaudeCliWorkerPool pool = new ClaudeCliWorkerPool(
                (model, sid, fork, cfg) -> new ClaudeCliWorker(binary.toString(), model, sid, fork, cfg, Duration.ofSeconds(5)),
                1, Duration.ofSeconds(2), null, Duration.ZERO, Duration.ofMinutes(15));
        try {
            ClaudeCliWorker work01 = pool.getOrCreateMain("run-1", null, "/accounts/work01");
            assertThat(pool.getOrCreateMain("run-1", null, "/accounts/work01")).isSameAs(work01);

            // 상한 1 이라 이전 워커의 자리를 돌려주지 않으면 여기서 자리 대기 시간 초과가 난다.
            ClaudeCliWorker work02 = pool.getOrCreateMain("run-1", null, "/accounts/work02");

            assertThat(work02).isNotSameAs(work01);
            assertThat(work02.getConfigDir()).isEqualTo("/accounts/work02");
            awaitDead(work01);
            assertThat(work01.isAlive()).isFalse();
            assertThat(pool.activeWorkerCount("run-1")).isEqualTo(1);
            assertThat(pool.getOrCreateMain("run-1", null, "/accounts/work02")).isSameAs(work02);
        } finally {
            pool.shutdownForRun("run-1");
        }
    }

    private ClaudeCliWorker start(String... turnLines) throws IOException {
        ClaudeCliWorker w = new ClaudeCliWorker(stub(turnLines).toString(), null, null, false, null, Duration.ofSeconds(10));
        w.start();
        return w;
    }

    private static List<UnifiedMessage> user(String text) {
        return List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, text));
    }

    /** 사용자 메시지가 한 줄 들어올 때마다 turnLines 를 차례로 내보내는 CLI 대역. */
    private Path stub(String... turnLines) throws IOException {
        StringBuilder sb = new StringBuilder("#!/bin/bash\n");
        sb.append("echo '").append(INIT).append("'\n");
        sb.append("while IFS= read -r line; do\n  if [ -z \"$line\" ]; then continue; fi\n");
        for (String l : turnLines) sb.append("  echo '").append(l).append("'\n");
        sb.append("done\n");
        Path p = tempDir.resolve("claude-stub-" + System.nanoTime() + ".sh");
        Files.writeString(p, sb.toString());
        Files.setPosixFilePermissions(p, EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        return p;
    }

    private static void awaitDead(ClaudeCliWorker w) {
        long deadline = System.currentTimeMillis() + 2000;
        while (w.isAlive() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        }
    }
}
