package com.platform.agent;

import com.platform.llm.model.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CR-030 PRD-209/210: 멀티에이전트 병렬/순차 조율.
 *
 * - 병렬 실행: CompletableFuture.allOf (Virtual Thread)
 * - 순차 실행: 직렬 호출
 * - 부모 세션에 자식 결과 병합 + 토큰 카운트 누적
 */
@Component
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    private final SubagentRunner subagentRunner;
    private final ExecutorService virtualThreadPool = Executors.newVirtualThreadPerTaskExecutor();

    public AgentOrchestrator(SubagentRunner subagentRunner) {
        this.subagentRunner = subagentRunner;
    }

    /**
     * 다수의 서브에이전트를 병렬 실행하고 결과 병합.
     */
    public OrchestratedResult runParallel(List<SubagentRequest> requests) {
        log.info("Parallel agent orchestration: {} agents", requests.size());

        List<CompletableFuture<SubagentResult>> futures = requests.stream()
                .map(req -> CompletableFuture.supplyAsync(() -> subagentRunner.run(req), virtualThreadPool))
                .toList();

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

        List<SubagentResult> results = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        return mergeResults(results);
    }

    /**
     * 다수의 서브에이전트를 순차 실행하고 결과 병합.
     * 이전 에이전트 실패 시 중단하지 않고 계속 진행.
     */
    public OrchestratedResult runSequential(List<SubagentRequest> requests) {
        log.info("Sequential agent orchestration: {} agents", requests.size());

        List<SubagentResult> results = new ArrayList<>();
        for (SubagentRequest req : requests) {
            SubagentResult result = subagentRunner.run(req);
            results.add(result);
        }

        return mergeResults(results);
    }

    /**
     * 단일 에이전트 실행 (편의 메서드).
     */
    public SubagentResult runSingle(SubagentRequest request) {
        return subagentRunner.run(request);
    }

    // ── 결과 병합 ──

    private OrchestratedResult mergeResults(List<SubagentResult> results) {
        int totalInputTokens = 0;
        int totalOutputTokens = 0;
        long totalDurationMs = 0;
        int successCount = 0;
        int failCount = 0;

        StringBuilder mergedOutput = new StringBuilder();

        for (SubagentResult r : results) {
            if (r.isSuccess()) {
                successCount++;
                if (r.output() != null) {
                    if (!mergedOutput.isEmpty()) mergedOutput.append("\n\n---\n\n");
                    mergedOutput.append(r.output());
                }
            } else {
                failCount++;
            }
            if (r.usage() != null) {
                totalInputTokens += r.usage().inputTokens();
                totalOutputTokens += r.usage().outputTokens();
            }
            totalDurationMs = Math.max(totalDurationMs, r.durationMs());
        }

        TokenUsage totalUsage = new TokenUsage(totalInputTokens, totalOutputTokens);

        return new OrchestratedResult(
                results, mergedOutput.toString(), totalUsage,
                totalDurationMs, successCount, failCount);
    }

    /**
     * 멀티에이전트 조율 결과.
     */
    public record OrchestratedResult(
            List<SubagentResult> results,
            String mergedOutput,
            TokenUsage totalUsage,
            long maxDurationMs,
            int successCount,
            int failCount
    ) {
        public boolean allSucceeded() {
            return failCount == 0;
        }
    }
}
