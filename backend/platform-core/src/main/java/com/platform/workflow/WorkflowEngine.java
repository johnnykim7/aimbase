package com.platform.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.domain.PendingApprovalEntity;
import com.platform.domain.WorkflowEntity;
import com.platform.domain.WorkflowRunEntity;
import com.platform.domain.master.PlatformWorkflowEntity;
import com.platform.monitoring.PlatformMetrics;
import com.platform.repository.PendingApprovalRepository;
import com.platform.repository.WorkflowRepository;
import com.platform.repository.WorkflowRunRepository;
import com.platform.tenant.TenantContext;
import com.platform.workflow.model.ErrorHandling;
import com.platform.workflow.model.WorkflowStep;
import com.platform.workflow.step.StepExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 워크플로우 DAG 실행 엔진.
 *
 * 주요 기능:
 * - Virtual Thread 기반 비동기 실행
 * - dependsOn 기반 위상 정렬 (Kahn's Algorithm)
 * - 스텝별 재시도 (ErrorHandling.retryMaxAttempts)
 * - CONDITION 스텝 → true/false 분기 캐스케이드 스킵
 * - HUMAN_INPUT 스텝 → PendingApproval 생성 후 일시 중단 / resume() 으로 재개
 */
@Component
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    private final WorkflowRepository workflowRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final PendingApprovalRepository pendingApprovalRepository;
    private final ObjectMapper objectMapper;
    private final Map<WorkflowStep.StepType, StepExecutor> executors;
    private final PlatformMetrics platformMetrics;

    public WorkflowEngine(WorkflowRepository workflowRepository,
                          WorkflowRunRepository workflowRunRepository,
                          PendingApprovalRepository pendingApprovalRepository,
                          ObjectMapper objectMapper,
                          List<StepExecutor> stepExecutors,
                          PlatformMetrics platformMetrics) {
        this.workflowRepository = workflowRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.pendingApprovalRepository = pendingApprovalRepository;
        this.objectMapper = objectMapper;
        this.executors = stepExecutors.stream()
                .collect(Collectors.toMap(StepExecutor::supports, e -> e));
        this.platformMetrics = platformMetrics;
        log.info("WorkflowEngine initialized with executors: {}", this.executors.keySet());
    }

    // ─── 공개 API ─────────────────────────────────────────────────────────

    /**
     * 워크플로우 비동기 실행 시작. WorkflowRunEntity를 즉시 반환하고 백그라운드에서 DAG 실행.
     */
    public WorkflowRunEntity execute(String workflowId, Map<String, Object> input, String sessionId) {
        WorkflowEntity workflowEntity = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow not found: " + workflowId));

        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setWorkflowId(workflowId);
        run.setSessionId(sessionId);
        run.setStatus("running");
        Map<String, Object> effectiveInput = input != null ? input : Map.of();
        run.setInputData(effectiveInput);
        run.setStepResults(new LinkedHashMap<>());
        // 디버그: inputData 키 및 값 길이 로깅
        log.info("Workflow '{}' run started. inputData keys: {}, value lengths: {}",
                workflowId,
                effectiveInput.keySet(),
                effectiveInput.entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                Map.Entry::getKey,
                                e -> e.getValue() != null ? String.valueOf(e.getValue()).length() : 0)));
        WorkflowRunEntity saved = workflowRunRepository.save(run);

        // Virtual Thread에 TenantContext 전파
        String tenantId = TenantContext.getTenantId();
        Thread.ofVirtual()
                .name("workflow-run-" + saved.getId())
                .start(() -> {
                    if (tenantId != null) TenantContext.setTenantId(tenantId);
                    try {
                        doExecuteAsync(workflowEntity, saved);
                    } finally {
                        TenantContext.clear();
                    }
                });

        return saved;
    }

    /**
     * 플랫폼 공용 워크플로우 실행.
     * Master DB의 PlatformWorkflowEntity를 사용하며, 실행 기록(WorkflowRunEntity)은 현재 테넌트 DB에 저장.
     */
    public WorkflowRunEntity executePlatform(PlatformWorkflowEntity platform,
                                              Map<String, Object> input, String sessionId) {
        // PlatformWorkflowEntity → WorkflowEntity로 변환 (기존 DAG 엔진 재사용)
        WorkflowEntity proxy = new WorkflowEntity();
        proxy.setId("platform:" + platform.getId());
        proxy.setName(platform.getName());
        proxy.setSteps(platform.getSteps());
        proxy.setErrorHandling(platform.getErrorHandling());
        proxy.setOutputSchema(platform.getOutputSchema());
        proxy.setTriggerConfig(platform.getTriggerConfig() != null ? platform.getTriggerConfig() : Map.of());

        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setWorkflowId(proxy.getId());
        run.setSessionId(sessionId);
        run.setStatus("running");
        run.setInputData(input != null ? input : Map.of());
        run.setStepResults(new LinkedHashMap<>());
        WorkflowRunEntity saved = workflowRunRepository.save(run);

        String tenantId = TenantContext.hasTenant() ? TenantContext.getTenantId() : null;
        Thread.ofVirtual()
                .name("platform-workflow-run-" + saved.getId())
                .start(() -> {
                    if (tenantId != null) TenantContext.setTenantId(tenantId);
                    try {
                        doExecuteAsync(proxy, saved);
                    } finally {
                        TenantContext.clear();
                    }
                });

        return saved;
    }

    /**
     * HUMAN_INPUT 스텝에서 일시 중단된 실행을 승인/거부 후 재개.
     */
    public WorkflowRunEntity resume(UUID runId, boolean approved, String reason) {
        WorkflowRunEntity run = workflowRunRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow run not found: " + runId));

        if (!"pending_approval".equals(run.getStatus())) {
            throw new IllegalStateException("Workflow run is not pending approval: " + runId);
        }

        // 대기 중인 승인 엔티티 업데이트
        pendingApprovalRepository.findByActionLogId(runId).stream()
                .filter(a -> "pending".equals(a.getStatus()))
                .forEach(a -> {
                    a.setStatus(approved ? "approved" : "rejected");
                    a.setReason(reason);
                    a.setResolvedAt(OffsetDateTime.now());
                    pendingApprovalRepository.save(a);
                });

        if (!approved) {
            run.setStatus("failed");
            run.setError(Map.of(
                    "reason", "Human approval rejected",
                    "detail", reason != null ? reason : ""
            ));
            run.setCompletedAt(OffsetDateTime.now());
            return workflowRunRepository.save(run);
        }

        // 승인 → HUMAN_INPUT 스텝 결과를 stepResults에 추가 후 재개
        String pausedAtStep = run.getCurrentStep();
        Map<String, Object> stepResults = new LinkedHashMap<>(
                run.getStepResults() != null ? run.getStepResults() : Map.of());
        if (pausedAtStep != null) {
            stepResults.put(pausedAtStep, Map.of(
                    "status", "approved",
                    "approved_at", OffsetDateTime.now().toString()
            ));
        }
        run.setStepResults(stepResults);
        run.setStatus("running");
        workflowRunRepository.save(run);

        WorkflowEntity workflowEntity = workflowRepository.findById(run.getWorkflowId())
                .orElseThrow(() -> new IllegalStateException("Workflow not found: " + run.getWorkflowId()));

        // Virtual Thread에 TenantContext 전파
        String tenantId = TenantContext.getTenantId();
        Thread.ofVirtual()
                .name("workflow-resume-" + runId)
                .start(() -> {
                    if (tenantId != null) TenantContext.setTenantId(tenantId);
                    try {
                        doExecuteAsync(workflowEntity, run);
                    } finally {
                        TenantContext.clear();
                    }
                });

        return run;
    }

    /**
     * 스텝 ID로 단일 스텝을 실행. {@link com.platform.workflow.step.ParallelStepExecutor}에서 사용.
     */
    public Map<String, Object> executeStepById(String stepId, StepContext context) {
        WorkflowEntity workflowEntity = workflowRepository.findById(context.workflowId())
                .orElseThrow(() -> new IllegalStateException("Workflow not found: " + context.workflowId()));

        List<WorkflowStep> steps = parseSteps(workflowEntity);
        WorkflowStep step = steps.stream()
                .filter(s -> stepId.equals(s.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Step not found in workflow: " + stepId));

        return executeWithRetry(step, context, parseErrorHandling(workflowEntity));
    }

    // ─── 내부 DAG 실행 ────────────────────────────────────────────────────

    private void doExecuteAsync(WorkflowEntity workflowEntity, WorkflowRunEntity run) {
        try {
            List<WorkflowStep> steps = parseSteps(workflowEntity);

            if (steps.isEmpty()) {
                log.warn("Workflow '{}' has no steps defined", workflowEntity.getId());
                run.setStatus("completed");
                run.setCompletedAt(OffsetDateTime.now());
                workflowRunRepository.save(run);
                return;
            }

            ErrorHandling errorHandling = parseErrorHandling(workflowEntity);

            // CR-007: output_schema가 있으면 마지막 LLM_CALL 스텝에 자동 주입
            if (workflowEntity.getOutputSchema() != null && !workflowEntity.getOutputSchema().isEmpty()) {
                injectOutputSchemaToLastLlmStep(steps, workflowEntity.getOutputSchema());
            }

            Map<String, WorkflowStep> stepMap = steps.stream()
                    .collect(Collectors.toMap(WorkflowStep::id, s -> s, (a, b) -> a, LinkedHashMap::new));

            // 저장된 stepResults로 컨텍스트 재구성 (HUMAN_INPUT 재개 시 이전 결과 유지)
            Map<String, Object> savedResults = run.getStepResults() != null
                    ? new LinkedHashMap<>(run.getStepResults()) : new LinkedHashMap<>();
            StepContext context = new StepContext(
                    run.getId().toString(),
                    workflowEntity.getId(),
                    run.getSessionId(),
                    run.getInputData() != null ? run.getInputData() : Map.of(),
                    savedResults
            );

            List<WorkflowStep> sortedSteps = topologicalSort(steps);
            Set<String> skippedSteps = new HashSet<>();

            for (WorkflowStep step : sortedSteps) {
                // 이미 완료된 스텝 스킵 (재개 시). CONDITION이면 분기 재적용.
                if (context.stepResults().containsKey(step.id())) {
                    if (step.type() == WorkflowStep.StepType.CONDITION) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> prevResult = (Map<String, Object>) context.stepResults().get(step.id());
                        applyConditionSkips(step, prevResult, stepMap, skippedSteps);
                    }
                    continue;
                }

                if (skippedSteps.contains(step.id())) {
                    log.debug("Run '{}': skipping step '{}' (not on active path)", run.getId(), step.id());
                    continue;
                }

                // HUMAN_INPUT → PendingApproval 생성 후 실행 중단
                if (step.type() == WorkflowStep.StepType.HUMAN_INPUT) {
                    handleHumanInput(step, run, context);
                    return;
                }

                // 현재 스텝 DB 업데이트
                run.setCurrentStep(step.id());
                workflowRunRepository.save(run);
                log.debug("Run '{}': executing step '{}' (type={})", run.getId(), step.id(), step.type());

                try {
                    long stepStart = System.currentTimeMillis();
                    Map<String, Object> result = executeWithRetry(step, context, errorHandling);
                    long stepEnd = System.currentTimeMillis();

                    // 타이밍 메타데이터 추가
                    Map<String, Object> enriched = new LinkedHashMap<>(result);
                    enriched.put("_startedAt", Instant.ofEpochMilli(stepStart).toString());
                    enriched.put("_completedAt", Instant.ofEpochMilli(stepEnd).toString());
                    enriched.put("_durationMs", stepEnd - stepStart);

                    context = context.withStepResult(step.id(), enriched);

                    // 진행 상태 DB에 저장
                    run.setStepResults(new LinkedHashMap<>(context.stepResults()));
                    workflowRunRepository.save(run);

                    log.debug("Run '{}': step '{}' succeeded ({}ms)", run.getId(), step.id(), stepEnd - stepStart);

                    // CONDITION 분기 처리
                    if (step.type() == WorkflowStep.StepType.CONDITION) {
                        applyConditionSkips(step, result, stepMap, skippedSteps);
                    }

                } catch (Exception e) {
                    log.error("Run '{}': step '{}' failed permanently: {}", run.getId(), step.id(), e.getMessage());
                    run.setStatus("failed");
                    run.setError(Map.of("step", step.id(), "error", e.getMessage()));
                    run.setCompletedAt(OffsetDateTime.now());
                    workflowRunRepository.saveAndFlush(run);
                    platformMetrics.recordWorkflowExecution("failed");
                    return;
                }
            }

            // 모든 스텝 완료
            run.setStatus("completed");
            run.setCompletedAt(OffsetDateTime.now());
            run.setStepResults(new LinkedHashMap<>(context.stepResults()));
            workflowRunRepository.saveAndFlush(run);
            platformMetrics.recordWorkflowExecution("completed");
            log.info("Run '{}' (workflow='{}') completed: {} step results",
                    run.getId(), workflowEntity.getId(), context.stepResults().size());

        } catch (Exception e) {
            log.error("Run '{}' encountered unexpected error: {}", run.getId(), e.getMessage(), e);
            run.setStatus("failed");
            run.setError(Map.of("error", e.getMessage()));
            run.setCompletedAt(OffsetDateTime.now());
            workflowRunRepository.saveAndFlush(run);
        }
    }

    private void handleHumanInput(WorkflowStep step, WorkflowRunEntity run, StepContext context) {
        log.info("Run '{}': paused at HUMAN_INPUT step '{}'", run.getId(), step.id());

        PendingApprovalEntity approval = new PendingApprovalEntity();
        approval.setActionLogId(run.getId());   // runId를 역참조 키로 사용
        approval.setPolicyId(step.id());         // 대기 중인 스텝 ID 기록
        approval.setApprovalChannel("workflow");

        if (step.config() != null && step.config().get("approvers") instanceof List<?> approvers) {
            approval.setApprovers(approvers.stream().map(Object::toString).toList());
        }

        if (step.timeoutMs() != null && step.timeoutMs() > 0) {
            approval.setTimeoutAt(OffsetDateTime.now().plusSeconds(step.timeoutMs() / 1000));
        }

        pendingApprovalRepository.save(approval);

        run.setCurrentStep(step.id());
        run.setStatus("pending_approval");
        run.setStepResults(new LinkedHashMap<>(context.stepResults()));
        workflowRunRepository.save(run);
    }

    private Map<String, Object> executeWithRetry(WorkflowStep step, StepContext context, ErrorHandling errorHandling) {
        StepExecutor executor = executors.get(step.type());
        if (executor == null) {
            throw new IllegalStateException("No executor registered for step type: " + step.type());
        }

        int maxAttempts = Math.max(1, errorHandling.retryMaxAttempts() + 1);
        long delayMs = errorHandling.retryDelayMs();
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return executor.execute(step, context);
            } catch (Exception e) {
                lastException = e;
                if (attempt < maxAttempts) {
                    log.warn("Step '{}' attempt {}/{} failed: {}. Retrying in {}ms...",
                            step.id(), attempt, maxAttempts, e.getMessage(), delayMs);
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during retry wait for step: " + step.id(), ie);
                    }
                }
            }
        }

        throw new RuntimeException(
                "Step '" + step.id() + "' failed after " + maxAttempts + " attempt(s): " + lastException.getMessage(),
                lastException);
    }

    /**
     * CONDITION 스텝 결과에 따라 선택되지 않은 분기와 그 하위 스텝을 스킵 목록에 추가.
     */
    private void applyConditionSkips(WorkflowStep conditionStep, Map<String, Object> result,
                                      Map<String, WorkflowStep> stepMap, Set<String> skippedSteps) {
        if (result == null || conditionStep.config() == null) return;

        String nextStep = result.get("next_step") instanceof String s ? s : null;
        String trueStep = conditionStep.config().get("true_step") instanceof String s ? s : null;
        String falseStep = conditionStep.config().get("false_step") instanceof String s ? s : null;

        if (nextStep == null) return;

        if (nextStep.equals(trueStep) && falseStep != null) {
            cascadeSkip(falseStep, stepMap, skippedSteps);
        } else if (nextStep.equals(falseStep) && trueStep != null) {
            cascadeSkip(trueStep, stepMap, skippedSteps);
        }
    }

    /**
     * 지정 스텝과 그 하위 스텝을 재귀적으로 스킵. 다른 활성 경로에서도 의존하는 스텝은 스킵하지 않음.
     */
    private void cascadeSkip(String stepId, Map<String, WorkflowStep> stepMap, Set<String> skipped) {
        if (skipped.contains(stepId) || !stepMap.containsKey(stepId)) return;
        skipped.add(stepId);

        for (WorkflowStep candidate : stepMap.values()) {
            if (candidate.dependsOn() == null || skipped.contains(candidate.id())) continue;
            if (!candidate.dependsOn().contains(stepId)) continue;

            // 모든 의존 스텝이 스킵 목록에 있을 때만 캐스케이드
            boolean allDepsSkipped = candidate.dependsOn().stream()
                    .allMatch(dep -> skipped.contains(dep) || !stepMap.containsKey(dep));
            if (allDepsSkipped) {
                cascadeSkip(candidate.id(), stepMap, skipped);
            }
        }
    }

    /**
     * CR-007: output_schema를 마지막 LLM_CALL 스텝의 config에 response_schema로 주입.
     * steps 리스트는 mutable이어야 함.
     */
    private void injectOutputSchemaToLastLlmStep(List<WorkflowStep> steps, Map<String, Object> outputSchema) {
        // 마지막 LLM_CALL 스텝 찾기 (역순 탐색)
        for (int i = steps.size() - 1; i >= 0; i--) {
            WorkflowStep step = steps.get(i);
            if (step.type() == WorkflowStep.StepType.LLM_CALL) {
                // 이미 response_schema가 있으면 스킵
                if (step.config() != null && step.config().containsKey("response_schema")) {
                    return;
                }
                // config에 response_schema 주입 (새 WorkflowStep으로 교체)
                Map<String, Object> newConfig = new HashMap<>(step.config() != null ? step.config() : Map.of());
                newConfig.put("response_schema", outputSchema);
                steps.set(i, new WorkflowStep(
                        step.id(), step.name(), step.type(), newConfig,
                        step.dependsOn(), step.onSuccess(), step.onFailure(), step.timeoutMs()));
                log.debug("Injected output_schema into last LLM_CALL step '{}'", step.id());
                return;
            }
        }
    }

    // ─── 파싱 헬퍼 ────────────────────────────────────────────────────────

    private List<WorkflowStep> parseSteps(WorkflowEntity entity) {
        List<Map<String, Object>> rawSteps = entity.getSteps();
        if (rawSteps == null || rawSteps.isEmpty()) return List.of();
        return rawSteps.stream()
                .map(raw -> objectMapper.convertValue(raw, WorkflowStep.class))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private ErrorHandling parseErrorHandling(WorkflowEntity entity) {
        Map<String, Object> raw = entity.getErrorHandling();
        if (raw == null) return ErrorHandling.defaults();
        try {
            return objectMapper.convertValue(raw, ErrorHandling.class);
        } catch (Exception e) {
            log.warn("Failed to parse errorHandling config, using defaults: {}", e.getMessage());
            return ErrorHandling.defaults();
        }
    }

    // ─── 위상 정렬 (Kahn's Algorithm) ─────────────────────────────────────

    private List<WorkflowStep> topologicalSort(List<WorkflowStep> steps) {
        Map<String, WorkflowStep> stepById = new LinkedHashMap<>();
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();

        for (WorkflowStep step : steps) {
            stepById.put(step.id(), step);
            inDegree.putIfAbsent(step.id(), 0);
        }

        for (WorkflowStep step : steps) {
            if (step.dependsOn() != null) {
                for (String dep : step.dependsOn()) {
                    inDegree.merge(step.id(), 1, Integer::sum);
                    dependents.computeIfAbsent(dep, k -> new ArrayList<>()).add(step.id());
                }
            }
        }

        Queue<String> queue = new LinkedList<>();
        inDegree.forEach((id, degree) -> {
            if (degree == 0) queue.add(id);
        });

        List<WorkflowStep> sorted = new ArrayList<>(steps.size());
        while (!queue.isEmpty()) {
            String id = queue.poll();
            WorkflowStep step = stepById.get(id);
            if (step != null) sorted.add(step);
            for (String dependent : dependents.getOrDefault(id, List.of())) {
                if (inDegree.merge(dependent, -1, Integer::sum) == 0) {
                    queue.add(dependent);
                }
            }
        }

        if (sorted.size() != steps.size()) {
            log.warn("Topological sort incomplete ({}/{} steps) — possible cycle. Using original order.",
                    sorted.size(), steps.size());
            return new ArrayList<>(steps);
        }

        return sorted;
    }
}
