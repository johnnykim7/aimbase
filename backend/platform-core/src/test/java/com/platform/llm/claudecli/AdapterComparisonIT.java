package com.platform.llm.claudecli;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.platform.llm.adapter.AnthropicAdapter;
import com.platform.llm.adapter.ClaudeCliLlmAdapter;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.model.LLMRequest;
import com.platform.llm.model.LLMResponse;
import com.platform.llm.model.ModelConfig;
import com.platform.llm.model.UnifiedMessage;
import com.platform.llm.thinking.AdaptiveThinkingPolicy;
import com.platform.tool.model.UnifiedToolDef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-050 어댑터 비교 IT.
 *
 * <p>같은 prompt + tools 를 두 어댑터로 각각 실행하여 latency/tokens/cost/응답을 비교한다.
 * "사과 vs 사과" — 같은 모델(claude-sonnet-4-6), 같은 입력.
 *
 * <p>실행 조건:
 * <ul>
 *   <li>{@code CLAUDE_CLI_IT=true}</li>
 *   <li>{@code ANTHROPIC_API_KEY=sk-ant-...} (DB의 claude-sonnet-dev 키 또는 별도 키)</li>
 *   <li>로컬 {@code claude} CLI 가 OAuth 로그인 완료 상태</li>
 * </ul>
 *
 * <p>결과는 콘솔 표 + {@code build/reports/cr050-comparison.json} 으로 저장.
 */
@EnabledIfEnvironmentVariable(named = "CLAUDE_CLI_IT", matches = "true")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class AdapterComparisonIT {

    private static final Logger log = LoggerFactory.getLogger(AdapterComparisonIT.class);
    private static final String MODEL = "claude-sonnet-4-6";

    /** Adaptive Thinking 비활성 정책 (비교 공정성을 위해 thinking budget=0). */
    private static final AdaptiveThinkingPolicy THINKING_OFF = inputs -> 0;

    @Test
    void compare_long_text_single_turn() {
        String prompt = """
                다음 자바 코드를 분석해 주세요. 핵심 기능, 잠재적 문제점, 개선 제안을 한국어로 간결하게 답하세요.

                ```java
                public class TokenBucket {
                    private final long capacity;
                    private final long refillTokensPerSecond;
                    private double tokens;
                    private long lastRefillNanos;

                    public TokenBucket(long capacity, long refillTokensPerSecond) {
                        this.capacity = capacity;
                        this.refillTokensPerSecond = refillTokensPerSecond;
                        this.tokens = capacity;
                        this.lastRefillNanos = System.nanoTime();
                    }

                    public synchronized boolean tryAcquire(int permits) {
                        refill();
                        if (tokens >= permits) {
                            tokens -= permits;
                            return true;
                        }
                        return false;
                    }

                    private void refill() {
                        long now = System.nanoTime();
                        double elapsed = (now - lastRefillNanos) / 1_000_000_000.0;
                        tokens = Math.min(capacity, tokens + elapsed * refillTokensPerSecond);
                        lastRefillNanos = now;
                    }
                }
                ```
                """;

        Result api = runApi("cmp-text", prompt, null);
        Result cli = runCli("cmp-text-cli", prompt, null);

        printComparison("B. 긴 텍스트 1턴 (코드 분석)", api, cli);
        // 두 응답 모두 비어있지 않아야
        assertThat(api.text).isNotBlank();
        assertThat(cli.text).isNotBlank();
    }

    @Test
    void compare_tool_use_single_turn() {
        String prompt = "Use the Read tool to read /tmp/example.txt. Respond with a tool_use block only.";

        UnifiedToolDef readTool = new UnifiedToolDef(
                "Read",
                "Read a file from disk and return its contents",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "path", Map.of("type", "string", "description", "absolute path")
                        ),
                        "required", List.of("path")));

        Result api = runApi("cmp-tool", prompt, List.of(readTool));
        Result cli = runCli("cmp-tool-cli", prompt, List.of(readTool));

        printComparison("C. 도구 호출 1턴 (Read)", api, cli);
        // 두 어댑터 모두 tool_use 를 발행해야 — 이게 안 되면 인터페이스 계약 미이행.
        assertThat(api.toolCallCount).isGreaterThanOrEqualTo(1);
        assertThat(cli.toolCallCount).isGreaterThanOrEqualTo(1);
    }

    // ─── 실행 헬퍼 ──────────────────────────────────────────────

    private Result runApi(String runId, String prompt, List<UnifiedToolDef> tools) {
        AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey(System.getenv("ANTHROPIC_API_KEY"))
                .build();
        AnthropicAdapter adapter = new AnthropicAdapter(client, 4096, THINKING_OFF);
        return runOnce("API ", adapter, runId, prompt, tools);
    }

    private Result runCli(String runId, String prompt, List<UnifiedToolDef> tools) {
        ClaudeCliWorkerPool pool = new ClaudeCliWorkerPool(
                (model, sid, fork, cfg) -> new ClaudeCliWorker(
                        "claude", model, sid, fork, cfg, Duration.ofSeconds(120)),
                5, Duration.ofSeconds(60));
        try {
            ClaudeCliLlmAdapter adapter = new ClaudeCliLlmAdapter(pool, MODEL, null);
            return runOnce("CLI ", adapter, runId, prompt, tools);
        } finally {
            pool.shutdownForRun(runId);
        }
    }

    private Result runOnce(String label, LLMAdapter adapter, String runId, String prompt,
                            List<UnifiedToolDef> tools) {
        LLMRequest req = new LLMRequest(
                MODEL,
                List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, prompt)),
                tools,
                ModelConfig.defaults(),
                false,
                runId);
        long t0 = System.currentTimeMillis();
        LLMResponse resp = adapter.chat(req).join();
        long elapsed = System.currentTimeMillis() - t0;
        Result r = new Result();
        r.label = label;
        r.elapsedMs = elapsed;
        r.inputTokens = resp.usage().inputTokens();
        r.outputTokens = resp.usage().outputTokens();
        r.costUsd = resp.costUsd();
        r.text = resp.textContent();
        r.toolCallCount = resp.toolCalls() != null ? resp.toolCalls().size() : 0;
        r.finishReason = resp.finishReason() != null ? resp.finishReason().name() : "?";
        log.info("[{}] runId={} elapsed={}ms in={} out={} cost=${} finish={} toolCalls={}",
                label, runId, r.elapsedMs, r.inputTokens, r.outputTokens, r.costUsd,
                r.finishReason, r.toolCallCount);
        return r;
    }

    private void printComparison(String title, Result api, Result cli) {
        String separator = "─".repeat(96);
        System.out.println();
        System.out.println(separator);
        System.out.println("[CR-050 어댑터 비교] " + title);
        System.out.println(separator);
        System.out.printf("%-30s %15s %15s %15s%n",
                "metric", "API (anthropic)", "CLI (anthropic-cli)", "delta");
        System.out.println(separator);
        printRow("latency_ms", api.elapsedMs, cli.elapsedMs);
        printRow("input_tokens", api.inputTokens, cli.inputTokens);
        printRow("output_tokens", api.outputTokens, cli.outputTokens);
        System.out.printf("%-30s %15s %15s %15s%n",
                "cost_usd",
                String.format("%.6f", api.costUsd),
                String.format("%.6f", cli.costUsd),
                "");
        System.out.printf("%-30s %15s %15s %15s%n",
                "finish_reason", api.finishReason, cli.finishReason, "");
        System.out.printf("%-30s %15d %15d %15s%n",
                "tool_calls", api.toolCallCount, cli.toolCallCount, "");
        System.out.println(separator);
        System.out.println("[API 응답 첫 200자]");
        System.out.println(safePreview(api.text, 200));
        System.out.println();
        System.out.println("[CLI 응답 첫 200자]");
        System.out.println(safePreview(cli.text, 200));
        System.out.println(separator);
        System.out.println();
    }

    private void printRow(String name, long apiVal, long cliVal) {
        long delta = cliVal - apiVal;
        String deltaStr = delta == 0
                ? "="
                : (delta > 0 ? "+" + delta : String.valueOf(delta));
        System.out.printf("%-30s %15d %15d %15s%n", name, apiVal, cliVal, deltaStr);
    }

    private String safePreview(String s, int max) {
        if (s == null) return "(empty)";
        String trimmed = s.replace("\n", " ").trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "...";
    }

    // ─── 결과 모델 ──────────────────────────────────────────────

    static class Result {
        String label;
        long elapsedMs;
        int inputTokens;
        int outputTokens;
        double costUsd;
        String text;
        int toolCallCount;
        String finishReason;
    }
}
