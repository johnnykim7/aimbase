package com.platform.workflow.step;

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

    public ParallelStepExecutor(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
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

        // CR-071 Phase 1: ClaudeCliBranchScope/ClaudeCliWorkerPool 의존 제거 (cli-runner 모듈 분리).
        // Phase 4 에서 ClaudeCliAdapter 가 신설되면 브랜치 fork 라이프사이클은 어댑터/Runner 측에서 관리.

        // VT 는 부모 ThreadLocal 을 상속하지 않으므로 TenantContext 를 캡처해 각 VT 안에서 재주입.
        // 누락 시 sub-step(AGENT_CALL→SubagentRunner.save 등)이 잘못된 DataSource(=master)로 라우팅되어
        // "relation \"subagent_runs\" does not exist" 로 실패 (DB-per-Tenant 격리, BIZ-003).
        final String tenantId = com.platform.tenant.TenantContext.getTenantId();

        // 각 스텝을 Virtual Thread로 병렬 실행
        List<CompletableFuture<Map.Entry<String, Map<String, Object>>>> futures = stepIds.stream()
                .map(stepId -> CompletableFuture.supplyAsync(
                        () -> {
                            if (tenantId != null) com.platform.tenant.TenantContext.setTenantId(tenantId);
                            try {
                                Map<String, Object> result = engine.executeStepById(stepId, context);
                                return Map.entry(stepId, result);
                            } finally {
                                com.platform.tenant.TenantContext.clear();
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
