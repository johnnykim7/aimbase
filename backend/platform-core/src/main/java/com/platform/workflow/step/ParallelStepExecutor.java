package com.platform.workflow.step;

import com.platform.llm.claudecli.ClaudeCliBranchScope;
import com.platform.llm.claudecli.ClaudeCliWorkerPool;
import com.platform.workflow.StepContext;
import com.platform.workflow.WorkflowEngine;
import com.platform.workflow.model.WorkflowStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * PARALLEL 스텝 실행기.
 * 지정된 스텝들을 Virtual Thread로 병렬 실행.
 *
 * config 형식:
 * {
 *   "steps": ["step2", "step3", "step4"]  // 병렬 실행할 스텝 ID 목록
 * }
 */
@Component
public class ParallelStepExecutor implements StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(ParallelStepExecutor.class);

    // 순환 의존성 방지: WorkflowEngine을 ApplicationContext를 통해 지연 로드
    private final ApplicationContext applicationContext;
    /** CR-050: 병렬 브랜치 fork 워커 라이프사이클. null 허용 — 피처 비활성 환경. */
    private final ClaudeCliWorkerPool claudeCliWorkerPool;

    public ParallelStepExecutor(ApplicationContext applicationContext,
                                ClaudeCliWorkerPool claudeCliWorkerPool) {
        this.applicationContext = applicationContext;
        this.claudeCliWorkerPool = claudeCliWorkerPool;
    }

    @Override
    public WorkflowStep.StepType supports() {
        return WorkflowStep.StepType.PARALLEL;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(WorkflowStep step, StepContext context) {
        Map<String, Object> config = step.config();

        Object stepsObj = config.get("steps");
        List<String> stepIds = stepsObj instanceof List ? (List<String>) stepsObj : List.of();

        if (stepIds.isEmpty()) {
            log.warn("PARALLEL step '{}' has no sub-steps configured", step.id());
            return Map.of("output", "no steps", "results", Map.of());
        }

        WorkflowEngine engine = applicationContext.getBean(WorkflowEngine.class);

        log.debug("PARALLEL step '{}': running {} sub-steps in parallel", step.id(), stepIds.size());

        // CR-050: 각 병렬 서브스텝은 독립 브랜치 — fork-session 워커로 캐시 재사용.
        // 부모 runId = context.workflowRunId(), branchKey = 서브스텝 id.
        String parentRunId = context.workflowRunId();

        // 각 스텝을 Virtual Thread로 병렬 실행
        List<CompletableFuture<Map.Entry<String, Map<String, Object>>>> futures = stepIds.stream()
                .map(stepId -> CompletableFuture.supplyAsync(
                        () -> {
                            try (ClaudeCliBranchScope ignored = ClaudeCliBranchScope.open(parentRunId, stepId)) {
                                Map<String, Object> result = engine.executeStepById(stepId, context);
                                return Map.entry(stepId, result);
                            } finally {
                                if (claudeCliWorkerPool != null) {
                                    try {
                                        claudeCliWorkerPool.releaseBranchWorker(parentRunId, stepId);
                                    } catch (Exception e) {
                                        log.warn("Release branch worker failed (run={}, branch={}): {}",
                                                parentRunId, stepId, e.getMessage());
                                    }
                                }
                            }
                        },
                        Executors.newVirtualThreadPerTaskExecutor()
                ))
                .toList();

        // 모든 완료 대기 + 결과 수집
        Map<String, Object> results = new LinkedHashMap<>();
        for (CompletableFuture<Map.Entry<String, Map<String, Object>>> future : futures) {
            try {
                Map.Entry<String, Map<String, Object>> entry = future.get();
                results.put(entry.getKey(), entry.getValue());
            } catch (Exception e) {
                log.error("Parallel sub-step failed: {}", e.getMessage());
                // 실패한 스텝도 결과에 포함
                results.put("unknown", Map.of("error", e.getMessage(), "status", "failed"));
            }
        }

        log.debug("PARALLEL step '{}' completed: {} results", step.id(), results.size());

        return Map.of(
                "output", "parallel_completed",
                "results", results,
                "step_count", stepIds.size()
        );
    }
}
