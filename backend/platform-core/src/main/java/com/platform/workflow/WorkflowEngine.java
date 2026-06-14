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
    /** CR-058: 스텝/런 상태 전이 이벤트 브로드캐스터. null 이면 발행 스킵(테스트 편의). */
    private final com.platform.workflow.event.WorkflowEventPublisher eventPublisher;
    /** CR-090: workflow_run_events 비동기 기록. null 허용(테스트 편의). */
    private final com.platform.workflow.event.WorkflowRunEventRecorder eventRecorder;

    // CR-107: run 의 격리 workspace 경로(세션 workspaceRef)를 StepContext 에 주입하기 위한 의존.
    // 옵셔널 — 미가용 시 workspacePath=null(기존 폴백 동작).
    private final com.platform.session.SessionStore sessionStore;

    /**
     * CR-105: 협조적 중지 요청 집합 — {@link #requestCancel(UUID)} 가 runId 를 넣고,
     * DAG/cyclic 실행 루프가 스텝 경계에서 {@link #isCancelRequested(String)} 로 검사한다.
     * 같은 JVM 인스턴스에서 도는 run 만 인터셉트 가능(VT fire-and-forget 모델, 멀티노드 시 인스턴스 로컬).
     */
    private final java.util.Set<String> cancelRequests =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public WorkflowEngine(WorkflowRepository workflowRepository,
                          WorkflowRunRepository workflowRunRepository,
                          PendingApprovalRepository pendingApprovalRepository,
                          ObjectMapper objectMapper,
                          List<StepExecutor> stepExecutors,
                          PlatformMetrics platformMetrics,
                          com.platform.workflow.event.WorkflowEventPublisher eventPublisher,
                          org.springframework.beans.factory.ObjectProvider<com.platform.workflow.event.WorkflowRunEventRecorder> eventRecorderProvider,
                          org.springframework.beans.factory.ObjectProvider<com.platform.session.SessionStore> sessionStoreProvider) {
        this.workflowRepository = workflowRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.pendingApprovalRepository = pendingApprovalRepository;
        this.objectMapper = objectMapper;
        this.executors = stepExecutors.stream()
                .collect(Collectors.toMap(StepExecutor::supports, e -> e));
        this.platformMetrics = platformMetrics;
        this.eventPublisher = eventPublisher;
        // ObjectProvider 로 옵셔널 주입 — 기존 테스트가 5-arg/6-arg 생성자 mock 으로 호환 유지
        this.eventRecorder = eventRecorderProvider != null ? eventRecorderProvider.getIfAvailable() : null;
        this.sessionStore = sessionStoreProvider != null ? sessionStoreProvider.getIfAvailable() : null;
        log.info("WorkflowEngine initialized with executors: {}", this.executors.keySet());
    }

    /**
     * CR-071 Phase 1: ClaudeCliWorkerPool 의존 제거. Phase 4 에서 ClaudeCliAdapter 가
     * Runner 측 정리 책임을 가지므로 워크플로우 엔진은 더 이상 워커 라이프사이클을 관리하지 않는다.
     */
    private void shutdownCliWorkers(String runId) {
        // no-op (CR-071)
    }

    // ─── 협조적 중지 (CR-105) ─────────────────────────────────────────────

    /**
     * 진행 중인 run 에 협조적 중지를 요청한다. 실행 루프가 다음 스텝 경계에서 이 요청을 감지해
     * 현재 스텝까지만 마치고 status=cancelled 로 종료한다(진행 중 스텝은 강제로 끊지 않음).
     *
     * <p>같은 JVM 에서 도는 run 에만 표식을 남긴다. 멀티노드 환경에서 다른 인스턴스가 실행 중인
     * run 은 이 메모리 플래그로 잡히지 않는다(향후 DB 폴링 보강 여지).
     *
     * @return 표식 등록 여부(이미 등록돼 있었으면 false)
     */
    public boolean requestCancel(UUID runId) {
        boolean added = cancelRequests.add(runId.toString());
        log.info("Cancel requested for run '{}' (newlyMarked={})", runId, added);
        return added;
    }

    /**
     * 워크플로우 run 중지(협조적). 컨트롤러 진입점.
     *
     * <ul>
     *   <li><b>running</b>: 중지 표식만 세운다. 백그라운드 실행 루프가 다음 스텝 경계에서
     *       감지해 cancelled 로 종료한다(현재 스텝은 끝까지 수행).</li>
     *   <li><b>pending_approval</b>: 백그라운드 스레드가 없으므로(승인 대기 중) 즉시 cancelled 로
     *       전이하고 종료 이벤트를 발행한다. 대기 중인 승인 엔티티도 정리한다.</li>
     *   <li>그 외(completed/failed/cancelled): 이미 종료된 run — 변경 없이 그대로 반환.</li>
     * </ul>
     *
     * @return 중지 처리 후의 run 엔티티
     * @throws IllegalArgumentException run 미존재 시
     */
    public WorkflowRunEntity cancelRun(UUID runId) {
        WorkflowRunEntity run = workflowRunRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow run not found: " + runId));

        String status = run.getStatus();
        if ("completed".equals(status) || "failed".equals(status) || "cancelled".equals(status)) {
            log.info("Cancel ignored for run '{}': already terminal (status={})", runId, status);
            return run;
        }

        if ("pending_approval".equals(status)) {
            // 대기 중인 승인 엔티티 정리
            pendingApprovalRepository.findByActionLogId(runId).stream()
                    .filter(a -> "pending".equals(a.getStatus()))
                    .forEach(a -> {
                        a.setStatus("cancelled");
                        a.setReason("workflow run cancelled");
                        a.setResolvedAt(OffsetDateTime.now());
                        pendingApprovalRepository.save(a);
                    });
            run.setStatus("cancelled");
            run.setCompletedAt(OffsetDateTime.now());
            workflowRunRepository.saveAndFlush(run);
            platformMetrics.recordWorkflowExecution("cancelled");
            if (eventPublisher != null) {
                eventPublisher.runCompleted(run.getId(), run.getParentRunId(), "cancelled", runDurationMs(run));
            }
            log.info("Run '{}' cancelled immediately from pending_approval", runId);
            return run;
        }

        // running (또는 그 외 진행 상태) — 표식만 세우고 백그라운드 루프에 맡긴다.
        requestCancel(runId);
        return run;
    }

    /** 실행 루프 체크포인트 — 이 run 에 중지 요청이 걸려 있는지. */
    private boolean isCancelRequested(String runId) {
        return cancelRequests.contains(runId);
    }

    /** run 종료 시 표식 정리(메모리 누수 방지). */
    private void clearCancel(String runId) {
        cancelRequests.remove(runId);
    }

    /**
     * 중지 요청이 감지되었을 때 run 을 cancelled 로 종료한다.
     * 현재까지의 stepResults 를 보존하고 종료 이벤트를 발행한다.
     */
    private void finishCancelled(WorkflowRunEntity run, StepContext context) {
        log.info("Run '{}': cancellation honored at step boundary", run.getId());
        run.setStatus("cancelled");
        run.setCompletedAt(OffsetDateTime.now());
        if (context != null) {
            run.setStepResults(new LinkedHashMap<>(context.stepResults()));
        }
        workflowRunRepository.saveAndFlush(run);
        platformMetrics.recordWorkflowExecution("cancelled");
        if (eventPublisher != null) {
            eventPublisher.runCompleted(run.getId(), run.getParentRunId(), "cancelled", runDurationMs(run));
        }
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
        run.setSessionId(resolveSessionId(sessionId));
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
        String runIdForShutdown = saved.getId().toString();
        Thread.ofVirtual()
                .name("workflow-run-" + saved.getId())
                .start(() -> {
                    if (tenantId != null) TenantContext.setTenantId(tenantId);
                    try {
                        doExecuteAsync(workflowEntity, saved);
                    } finally {
                        // CR-050 PRD-309: 정상/예외 무관 CLI 워커 정리 (프로세스 누수 방지)
                        shutdownCliWorkers(runIdForShutdown);
                        clearCancel(runIdForShutdown); // 중지 표식 정리 (메모리 누수 방지)
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
        run.setSessionId(resolveSessionId(sessionId));
        run.setStatus("running");
        run.setInputData(input != null ? input : Map.of());
        run.setStepResults(new LinkedHashMap<>());
        WorkflowRunEntity saved = workflowRunRepository.save(run);

        String tenantId = TenantContext.hasTenant() ? TenantContext.getTenantId() : null;
        String runIdForShutdown = saved.getId().toString();
        Thread.ofVirtual()
                .name("platform-workflow-run-" + saved.getId())
                .start(() -> {
                    if (tenantId != null) TenantContext.setTenantId(tenantId);
                    try {
                        doExecuteAsync(proxy, saved);
                    } finally {
                        shutdownCliWorkers(runIdForShutdown);
                        clearCancel(runIdForShutdown); // 중지 표식 정리 (메모리 누수 방지)
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
        String runIdForShutdown = run.getId().toString();
        Thread.ofVirtual()
                .name("workflow-resume-" + runId)
                .start(() -> {
                    if (tenantId != null) TenantContext.setTenantId(tenantId);
                    try {
                        doExecuteAsync(workflowEntity, run);
                    } finally {
                        shutdownCliWorkers(runIdForShutdown);
                        clearCancel(runIdForShutdown); // 중지 표식 정리 (메모리 누수 방지)
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

    /**
     * CR-065: SUB_WORKFLOW 스텝이 호출하는 자식 워크플로우 동기 실행 진입점.
     *
     * <p>{@link com.platform.workflow.step.SubWorkflowStepExecutor} 가
     * {@link org.springframework.context.ApplicationContext} 지연 로드로 호출한다
     * ({@link com.platform.workflow.step.ParallelStepExecutor} 와 동일한 순환의존 회피 패턴).
     *
     * <p>기존 인라인 실행(executor 안에서 서브스텝 순차 실행)을 자식
     * {@link WorkflowRunEntity} 별도 레코드로 승격한다. 자식 run 은
     * {@code parent_run_id} / {@code parent_step_id} 가 채워져 실존 트리로 저장되며,
     * 자식 전용 stepResults 기록 + STEP_START/END 이벤트(부모 runId 와 분리)가 발행된다.
     *
     * <p><b>동기</b> 실행이다 — 부모 스텝이 자식의 최종 output 을 받아 다음 스텝에 넘겨야
     * 하므로 VirtualThread 를 띄우지 않는다. 호출 스레드의 TenantContext 를 그대로 사용한다.
     *
     * <p><b>자식 HUMAN_INPUT 금지</b>: 동기 경로라 일시중단/재개가 불가능하므로 자식
     * 워크플로우에 HUMAN_INPUT 스텝이 있으면 즉시 거부한다.
     *
     * @param platform     실행할 플랫폼 공용 워크플로우 (active 검증은 호출부 책임)
     * @param subInput     변수치환 완료된 서브 워크플로우 입력
     * @param parentRunId  부모 run UUID
     * @param parentStepId 이 자식을 트리거한 부모 스텝 ID
     * @param sessionId    부모 run 의 sessionId (자식도 동일 세션 공유)
     * @return 자식 마지막 스텝 output + {@code sub_workflow_id}(기존 호환) + {@code sub_workflow_run_id}(신규 자식 UUID)
     */
    public Map<String, Object> executeSubWorkflowSync(PlatformWorkflowEntity platform,
                                                      Map<String, Object> subInput,
                                                      UUID parentRunId,
                                                      String parentStepId,
                                                      String sessionId) {
        String workflowId = "platform:" + platform.getId();

        // PlatformWorkflowEntity → WorkflowEntity 프록시 (executePlatform 과 동일 변환)
        WorkflowEntity proxy = new WorkflowEntity();
        proxy.setId(workflowId);
        proxy.setName(platform.getName());
        proxy.setSteps(platform.getSteps());
        proxy.setErrorHandling(platform.getErrorHandling());
        proxy.setOutputSchema(platform.getOutputSchema());
        proxy.setTriggerConfig(platform.getTriggerConfig() != null ? platform.getTriggerConfig() : Map.of());

        List<WorkflowStep> steps = parseSteps(proxy);

        // 자식 run 레코드 생성 — parent_run_id / parent_step_id 채워 실존 트리로 저장
        WorkflowRunEntity child = new WorkflowRunEntity();
        child.setWorkflowId(workflowId);
        child.setSessionId(resolveSessionId(sessionId));
        child.setStatus("running");
        child.setInputData(subInput != null ? subInput : Map.of());
        child.setStepResults(new LinkedHashMap<>());
        child.setParentRunId(parentRunId);
        child.setParentStepId(parentStepId);
        WorkflowRunEntity savedChild = workflowRunRepository.save(child);

        log.info("SUB_WORKFLOW child run '{}' created (parent={}, step={}, workflow={}, {} steps)",
                savedChild.getId(), parentRunId, parentStepId, workflowId, steps.size());

        if (steps.isEmpty()) {
            savedChild.setStatus("completed");
            savedChild.setCompletedAt(OffsetDateTime.now());
            workflowRunRepository.save(savedChild);
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("output", "");
            empty.put("sub_workflow_id", platform.getId());
            empty.put("sub_workflow_run_id", savedChild.getId().toString());
            return empty;
        }

        // 자식에 HUMAN_INPUT 이 있으면 동기 경로상 처리 불가 → 거부 (자식 run 도 failed 로 마감)
        boolean hasHumanInput = steps.stream()
                .anyMatch(s -> s.type() == WorkflowStep.StepType.HUMAN_INPUT);
        if (hasHumanInput) {
            savedChild.setStatus("failed");
            savedChild.setError(Map.of("reason", "sub_workflow_human_input_unsupported"));
            savedChild.setCompletedAt(OffsetDateTime.now());
            workflowRunRepository.saveAndFlush(savedChild);
            throw new IllegalStateException(
                    "SUB_WORKFLOW '" + platform.getId()
                            + "' contains HUMAN_INPUT step which is not supported in synchronous child execution");
        }

        ErrorHandling errorHandling = parseErrorHandling(proxy);

        // CR-007: output_schema → 마지막 LLM_CALL 스텝 자동 주입 (부모 경로와 동일)
        if (proxy.getOutputSchema() != null && !proxy.getOutputSchema().isEmpty()) {
            injectOutputSchemaToLastLlmStep(steps, proxy.getOutputSchema());
        }

        try {
            Map<String, Object> lastResult = runChildSteps(savedChild, steps, errorHandling);

            savedChild.setStatus("completed");
            savedChild.setCompletedAt(OffsetDateTime.now());
            workflowRunRepository.saveAndFlush(savedChild);
            if (eventPublisher != null) {
                long durationMs = savedChild.getCompletedAt() != null && savedChild.getStartedAt() != null
                        ? savedChild.getCompletedAt().toInstant().toEpochMilli()
                                - savedChild.getStartedAt().toInstant().toEpochMilli()
                        : 0L;
                eventPublisher.runCompleted(savedChild.getId(), parentRunId, "completed", durationMs);
            }

            // 마지막 스텝 output + 기존 호환 필드 + 신규 자식 run id
            Map<String, Object> result = new LinkedHashMap<>(lastResult);
            result.put("sub_workflow_id", platform.getId());
            result.put("sub_workflow_run_id", savedChild.getId().toString());
            return result;

        } catch (Exception e) {
            savedChild.setStatus("failed");
            savedChild.setError(Map.of("error", e.getMessage()));
            savedChild.setCompletedAt(OffsetDateTime.now());
            workflowRunRepository.saveAndFlush(savedChild);
            if (eventPublisher != null) {
                long durationMs = savedChild.getStartedAt() != null
                        ? System.currentTimeMillis() - savedChild.getStartedAt().toInstant().toEpochMilli() : 0L;
                eventPublisher.runCompleted(savedChild.getId(), parentRunId, "failed", durationMs);
            }
            // 부모 스텝 실패로 전파
            throw new RuntimeException(
                    "SUB_WORKFLOW child run '" + savedChild.getId() + "' failed: " + e.getMessage(), e);
        }
    }

    /**
     * CR-065: 자식 run 의 DAG 스텝을 동기 1-pass 실행한다.
     *
     * <p>{@link #doExecuteAsync} 의 핵심 루프(위상정렬 + 순차 + executeWithRetry +
     * CONDITION 분기 + STEP_START/END 이벤트)를 자식 run 컨텍스트에 적용한 동기 버전.
     * HUMAN_INPUT 은 호출부에서 사전 차단되므로 여기서는 다루지 않는다.
     *
     * @return 마지막으로 성공한 스텝의 결과 (없으면 {@code {"output":""}})
     */
    private Map<String, Object> runChildSteps(WorkflowRunEntity child, List<WorkflowStep> steps,
                                              ErrorHandling errorHandling) {
        Map<String, WorkflowStep> stepMap = steps.stream()
                .collect(Collectors.toMap(WorkflowStep::id, s -> s, (a, b) -> a, LinkedHashMap::new));

        StepContext context = new StepContext(
                child.getId().toString(),
                child.getWorkflowId(),
                child.getSessionId(),
                child.getInputData() != null ? child.getInputData() : Map.of(),
                new LinkedHashMap<>()).withWorkspacePath(resolveWorkspacePath(child.getSessionId())
        );

        List<WorkflowStep> sortedSteps = topologicalSort(steps);
        Set<String> skippedSteps = new HashSet<>();
        Map<String, Object> lastResult = Map.of("output", "");

        for (WorkflowStep step : sortedSteps) {
            if (skippedSteps.contains(step.id())) {
                log.debug("Child run '{}': skipping step '{}' (not on active path)", child.getId(), step.id());
                continue;
            }

            child.setCurrentStep(step.id());
            workflowRunRepository.save(child);

            long stepStart = System.currentTimeMillis();
            Instant startedAt = Instant.ofEpochMilli(stepStart);
            if (eventPublisher != null) {
                eventPublisher.stepRunning(child.getId(), child.getParentRunId(), step.id(), startedAt);
            }
            if (eventRecorder != null) {
                eventRecorder.stepStart(child.getId(), step.id(), step.type().name());
            }

            Map<String, Object> result = executeWithRetry(step, context, errorHandling);
            long stepEnd = System.currentTimeMillis();
            Instant completedAt = Instant.ofEpochMilli(stepEnd);

            Map<String, Object> enriched = new LinkedHashMap<>(result);
            enriched.put("_startedAt", startedAt.toString());
            enriched.put("_completedAt", completedAt.toString());
            enriched.put("_durationMs", stepEnd - stepStart);

            context = applyStepResult(step, context, enriched);
            lastResult = result;

            child.setStepResults(new LinkedHashMap<>(context.stepResults()));
            workflowRunRepository.save(child);

            if (eventPublisher != null) {
                Object subRef = step.type() == WorkflowStep.StepType.SUB_WORKFLOW
                        ? result.get("sub_workflow_id") : null;
                eventPublisher.stepCompleted(
                        child.getId(), child.getParentRunId(), step.id(),
                        startedAt, completedAt,
                        subRef != null ? subRef.toString() : null,
                        previewOutput(result));
            }
            if (eventRecorder != null) {
                eventRecorder.stepEnd(child.getId(), step.id(), stepEnd - stepStart, estimateResultSize(result), resultBody(result));
            }

            if (step.type() == WorkflowStep.StepType.CONDITION) {
                applyConditionSkips(step, result, stepMap, skippedSteps);
            }
        }

        return lastResult;
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

            // CR-084 P3: graph_mode 분기. "cyclic" 명시 시에만 워크리스트 스케줄러.
            // null/"dag" 는 아래 기존 1-pass 로직 그대로 — 하위호환 절대 보장.
            if (isCyclicMode(workflowEntity)) {
                executeCyclic(workflowEntity, run, steps, errorHandling);
                return;
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
            ).withWorkspacePath(resolveWorkspacePath(run.getSessionId()));

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

                // 협조적 중지 체크포인트 — 다음 스텝 실행 전에 중지 요청을 감지하면 cancelled 로 종료.
                if (isCancelRequested(run.getId().toString())) {
                    finishCancelled(run, context);
                    return;
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
                    Instant startedAt = Instant.ofEpochMilli(stepStart);
                    // CR-058: 스텝 시작 이벤트.
                    if (eventPublisher != null) {
                        eventPublisher.stepRunning(run.getId(), run.getParentRunId(), step.id(), startedAt);
                    }
                    // CR-090: STEP_START
                    if (eventRecorder != null) {
                        eventRecorder.stepStart(run.getId(), step.id(), step.type().name());
                    }

                    Map<String, Object> result = executeWithRetry(step, context, errorHandling);
                    long stepEnd = System.currentTimeMillis();
                    Instant completedAt = Instant.ofEpochMilli(stepEnd);

                    // 타이밍 메타데이터 추가
                    Map<String, Object> enriched = new LinkedHashMap<>(result);
                    enriched.put("_startedAt", startedAt.toString());
                    enriched.put("_completedAt", completedAt.toString());
                    enriched.put("_durationMs", stepEnd - stepStart);

                    context = applyStepResult(step, context, enriched);  // CR-085 P2: 채널 reducer

                    // 진행 상태 DB에 저장
                    run.setStepResults(new LinkedHashMap<>(context.stepResults()));
                    workflowRunRepository.save(run);

                    log.debug("Run '{}': step '{}' succeeded ({}ms)", run.getId(), step.id(), stepEnd - stepStart);

                    // CR-058: 스텝 완료 이벤트. subWorkflowId 는 SUB_WORKFLOW 스텝 결과에서 추출.
                    if (eventPublisher != null) {
                        Object subRef = step.type() == WorkflowStep.StepType.SUB_WORKFLOW
                                ? result.get("sub_workflow_id") : null;
                        eventPublisher.stepCompleted(
                                run.getId(), run.getParentRunId(), step.id(),
                                startedAt, completedAt,
                                subRef != null ? subRef.toString() : null,
                                previewOutput(result));
                    }
                    // CR-090: STEP_END / CR-102: 결과 본문
                    if (eventRecorder != null) {
                        eventRecorder.stepEnd(run.getId(), step.id(), stepEnd - stepStart, estimateResultSize(result), resultBody(result));
                    }

                    // CONDITION 분기 처리
                    if (step.type() == WorkflowStep.StepType.CONDITION) {
                        applyConditionSkips(step, result, stepMap, skippedSteps);
                    }

                } catch (Exception e) {
                    log.error("Run '{}': step '{}' failed permanently: {}", run.getId(), step.id(), e.getMessage());
                    long failedAtMs = System.currentTimeMillis();
                    run.setStatus("failed");
                    run.setError(Map.of("step", step.id(), "error", e.getMessage()));
                    run.setCompletedAt(OffsetDateTime.now());
                    workflowRunRepository.saveAndFlush(run);
                    platformMetrics.recordWorkflowExecution("failed");
                    // CR-058: 스텝 실패 + 런 종료 이벤트.
                    if (eventPublisher != null) {
                        eventPublisher.stepFailed(run.getId(), run.getParentRunId(), step.id(),
                                null, Instant.ofEpochMilli(failedAtMs), e.getMessage());
                        long durationMs = run.getStartedAt() != null
                                ? failedAtMs - run.getStartedAt().toInstant().toEpochMilli() : 0L;
                        eventPublisher.runCompleted(run.getId(), run.getParentRunId(), "failed", durationMs);
                    }
                    // CR-090: STEP_FAILED (attempts 는 executeWithRetry 안 retry 횟수 반영 — 메시지 파싱 대신 errorHandling.retryMaxAttempts+1 상한으로 근사)
                    if (eventRecorder != null) {
                        eventRecorder.stepFailed(run.getId(), step.id(),
                                run.getStartedAt() != null
                                        ? failedAtMs - run.getStartedAt().toInstant().toEpochMilli() : 0L,
                                e.getMessage(),
                                errorHandling.retryMaxAttempts() + 1);
                    }
                    return;
                }
            }

            // 모든 스텝 완료
            run.setStatus("completed");
            run.setCompletedAt(OffsetDateTime.now());
            run.setStepResults(new LinkedHashMap<>(context.stepResults()));
            workflowRunRepository.saveAndFlush(run);
            platformMetrics.recordWorkflowExecution("completed");
            // CR-058: 런 종료 이벤트.
            if (eventPublisher != null) {
                long durationMs = run.getCompletedAt() != null && run.getStartedAt() != null
                        ? run.getCompletedAt().toInstant().toEpochMilli() - run.getStartedAt().toInstant().toEpochMilli()
                        : 0L;
                eventPublisher.runCompleted(run.getId(), run.getParentRunId(), "completed", durationMs);
            }
            log.info("Run '{}' (workflow='{}') completed: {} step results",
                    run.getId(), workflowEntity.getId(), context.stepResults().size());

        } catch (Exception e) {
            log.error("Run '{}' encountered unexpected error: {}", run.getId(), e.getMessage(), e);
            run.setStatus("failed");
            run.setError(Map.of("error", e.getMessage()));
            run.setCompletedAt(OffsetDateTime.now());
            workflowRunRepository.saveAndFlush(run);
            if (eventPublisher != null) {
                long durationMs = run.getCompletedAt() != null && run.getStartedAt() != null
                        ? run.getCompletedAt().toInstant().toEpochMilli() - run.getStartedAt().toInstant().toEpochMilli()
                        : 0L;
                eventPublisher.runCompleted(run.getId(), run.getParentRunId(), "failed", durationMs);
            }
        }
    }

    private void handleHumanInput(WorkflowStep step, WorkflowRunEntity run, StepContext context) {
        log.info("Run '{}': paused at HUMAN_INPUT step '{}'", run.getId(), step.id());

        PendingApprovalEntity approval = new PendingApprovalEntity();
        approval.setActionLogId(run.getId());   // runId를 역참조 키로 사용
        approval.setPolicyId(step.id());         // 대기 중인 스텝 ID 기록
        approval.setApprovalChannel("workflow");

        List<String> approvers = List.of();
        if (step.config() != null && step.config().get("approvers") instanceof List<?> rawApprovers) {
            approvers = rawApprovers.stream().map(Object::toString).toList();
            approval.setApprovers(approvers);
        }

        if (step.timeoutMs() != null && step.timeoutMs() > 0) {
            approval.setTimeoutAt(OffsetDateTime.now().plusSeconds(step.timeoutMs() / 1000));
        }

        pendingApprovalRepository.save(approval);

        run.setCurrentStep(step.id());
        run.setStatus("pending_approval");
        run.setStepResults(new LinkedHashMap<>(context.stepResults()));
        workflowRunRepository.save(run);

        // CR-058: 위젯이 소비앱 결재 플로우를 트리거할 수 있도록 approval 이벤트 발행.
        if (eventPublisher != null) {
            String reason = null;
            if (step.config() != null && step.config().get("reason") instanceof String r) reason = r;
            eventPublisher.approvalRequired(
                    run.getId(), run.getParentRunId(), step.id(),
                    step.id(),  // policyId 대용 (HUMAN_INPUT 은 스텝 자체가 정책)
                    reason,
                    approvers,
                    approval.getTimeoutAt() != null ? approval.getTimeoutAt().toInstant() : null);
        }
    }

    /**
     * CR-058: 스텝 결과에서 위젯 표시용 미리보기만 추출 (outputPreview).
     * 큰 필드/내부 타이밍 메타는 제외하여 SSE payload 크기를 제한한다.
     */
    private Map<String, Object> previewOutput(Map<String, Object> result) {
        if (result == null || result.isEmpty()) return null;
        Map<String, Object> preview = new LinkedHashMap<>();
        int count = 0;
        for (Map.Entry<String, Object> e : result.entrySet()) {
            if (e.getKey().startsWith("_")) continue;        // 타이밍 메타 제외
            if (count++ >= 5) break;                          // 상위 5필드만
            Object v = e.getValue();
            if (v instanceof String s && s.length() > 200) {
                preview.put(e.getKey(), s.substring(0, 200) + "…");
            } else {
                preview.put(e.getKey(), v);
            }
        }
        return preview.isEmpty() ? null : preview;
    }

    /**
     * CR-085 P2: 스텝 결과를 컨텍스트에 병합하되 step.config 의 채널 reducer 를 적용.
     *
     * <p>{@code config.output_channel} 미지정 시 기존 {@link StepContext#withStepResult(String, Map)}
     * 와 100% 동일 동작 — DAG/cyclic 어느 경로든 reducer 미사용 워크플로우는 바이트 동일.
     */
    private StepContext applyStepResult(WorkflowStep step, StepContext context, Map<String, Object> enriched) {
        Map<String, Object> cfg = step.config();
        Object channel = cfg != null ? cfg.get("output_channel") : null;
        if (cfg == null || channel == null || channel.toString().isBlank()) {
            return context.withStepResult(step.id(), enriched);  // 기존 경로 — 동작 변화 0
        }
        Object reduce = cfg.get("reduce");
        return context.withStepResult(step.id(), enriched,
                channel.toString(), reduce != null ? reduce.toString() : "replace");
    }

    /**
     * CR-090: 스텝 결과의 대략적 크기 — payload 크기 미리보기용 메타.
     * 정확한 직렬화 비용 회피를 위해 String.valueOf 길이로 근사한다.
     */
    private int estimateResultSize(Map<String, Object> result) {
        if (result == null || result.isEmpty()) return 0;
        Object out = result.get("output");
        // 구조화 출력이면 output 이 빈 문자열 — resultBody 와 동일하게 result 전체 크기로 폴백 (0 chars 오표시 방지)
        if (out instanceof String s && !s.isBlank()) return s.length();
        return String.valueOf(result).length();
    }

    /**
     * CR-102: 단계 결과 본문 전문 — 품질 분석(단계 간 데이터 전달 검토)용. 절단 없음.
     * output 이 단일 String 이면 그대로(가독), 그 외엔 result 전체를 JSON 직렬화.
     */
    private String resultBody(Map<String, Object> result) {
        if (result == null || result.isEmpty()) return null;
        Object out = result.get("output");
        // 구조화 출력이면 output(textContent)이 빈 문자열 — result 전체 JSON 으로 폴백해야 정독 가능
        if (out instanceof String s && !s.isBlank()) return s;
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return String.valueOf(result);
        }
    }

    /**
     * 호출부가 sessionId 를 null/blank 로 넘기면 workflow-run-{uuid} 로 자동 채운다.
     * 워크플로우 내부 스텝 중 일부(예: AGENT_CALL 의 SubagentRunEntity.parent_session_id NOT NULL)
     * 는 sessionId 가 null 이면 INSERT 자체가 거부된다. 호출부 부담을 줄이기 위해 엔진에서 안전망을 둔다.
     */
    private String resolveSessionId(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            return sessionId;
        }
        return "workflow-run-" + UUID.randomUUID();
    }

    /**
     * CR-107: run 의 격리 workspace 절대경로를 세션 workspaceRef 에서 푼다.
     * TOOL_CALL 스텝이 ToolContext.workspacePath 로 전파해 도구(download_file/file_write/bash 등)가
     * default/general 폴백 대신 이 run 과 AGENT_CALL 이 공유하는 workspace 에 쓰게 한다.
     * SessionStore 미가용/미매핑 시 null → 기존 폴백 동작 유지(하위호환).
     */
    private String resolveWorkspacePath(String sessionId) {
        if (sessionStore == null || sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            return sessionStore.getWorkspaceRef(sessionId);
        } catch (RuntimeException e) {
            log.debug("resolveWorkspacePath failed for session {}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    private Map<String, Object> executeWithRetry(WorkflowStep step, StepContext context, ErrorHandling errorHandling) {
        StepExecutor executor = executors.get(step.type());
        if (executor == null) {
            throw new IllegalStateException("No executor registered for step type: " + step.type());
        }

        int maxAttempts = Math.max(1, errorHandling.retryMaxAttempts() + 1);
        long delayMs = errorHandling.retryDelayMs();
        Exception lastException = null;
        // CR-106: 다음 attempt 에 직전 실패 메시지를 전달 — AgentCallStepExecutor 가 turn timeout 류 판정에 사용.
        StepContext attemptContext = context;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return executor.execute(step, attemptContext);
            } catch (Exception e) {
                lastException = e;
                // CR-106: 다음 attempt 컨텍스트에 직전 실패 메시지 주입 (timeout 류 이어하기 판정용).
                attemptContext = context.withRetryFailure(e.getMessage());
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

    // ─── CR-084 P3: Cyclic 워크리스트 스케줄러 ────────────────────────────

    /** 그래프 레벨 무한루프 방어 — run 당 총 스텝 실행 절대 상한 (config override 불가). */
    static final int STEP_BUDGET_CEILING = 200;
    /** config.max_total_steps 미지정 시 기본값. */
    static final int STEP_BUDGET_DEFAULT = 50;

    boolean isCyclicMode(WorkflowEntity entity) {
        return "cyclic".equalsIgnoreCase(entity.getGraphMode());
    }

    /**
     * CR-084 P3: graph_mode=cyclic 워크플로우 실행기.
     *
     * <p>기존 DAG 1-pass(doExecuteAsync 본문)와 완전 분리된 별도 경로다. 위상정렬 대신
     * 워크리스트(worklist)로 노드를 하나씩 실행하며, 각 노드 실행 후 "다음 노드"를
     * 결정해 worklist 에 push 한다. 같은 노드가 여러 번 실행될 수 있어 임의 cycle 지원.
     *
     * <p>다음 노드 결정 규칙:
     * <ul>
     *   <li>CONDITION / ROUTER → 실행 결과의 {@code next_step} (N-way 분기)</li>
     *   <li>그 외 스텝 → {@code onSuccess} (WorkflowStep 기존 필드를 그래프 엣지로 재사용)</li>
     *   <li>다음 노드가 없거나 "__end__" → 해당 경로 종료</li>
     * </ul>
     *
     * <p>무한루프 방어: run 당 총 스텝 실행 횟수가 budget 초과 시 failed
     * ({@code error.reason=step_budget_exceeded}). budget = config.max_total_steps
     * (기본 {@value #STEP_BUDGET_DEFAULT}, 절대 상한 {@value #STEP_BUDGET_CEILING}).
     * EVALUATOR_LOOP 의 MAX_ITERATIONS_CEILING 과는 독립된 그래프 레벨 방어선.
     *
     * <p><b>P3 범위 한정</b>: HUMAN_INPUT 일시중단 후 재개(resume) 시 worklist 복원은
     * P4 범위. P3 의 cyclic 워크플로우가 HUMAN_INPUT 을 만나면 일시중단까지만 동작하고
     * resume 은 P4 까지 미지원(주석으로 명시).
     */
    private void executeCyclic(WorkflowEntity workflowEntity, WorkflowRunEntity run,
                               List<WorkflowStep> steps, ErrorHandling errorHandling) {
        try {
            Map<String, WorkflowStep> stepMap = steps.stream()
                    .collect(Collectors.toMap(WorkflowStep::id, s -> s, (a, b) -> a, LinkedHashMap::new));

            int budget = resolveStepBudget(workflowEntity);

            Map<String, Object> savedResults = run.getStepResults() != null
                    ? new LinkedHashMap<>(run.getStepResults()) : new LinkedHashMap<>();
            StepContext context = new StepContext(
                    run.getId().toString(),
                    workflowEntity.getId(),
                    run.getSessionId(),
                    run.getInputData() != null ? run.getInputData() : Map.of(),
                    savedResults).withWorkspacePath(resolveWorkspacePath(run.getSessionId()));

            Deque<String> worklist = new ArrayDeque<>();
            int executed;

            // CR-084 P4: HUMAN_INPUT 중단 후 재개 — pending_worklist 복원 (처음부터 다시 돌지 않음).
            Map<String, Object> pending = run.getPendingWorklist();
            if (pending != null && pending.get("worklist") instanceof List<?> savedList) {
                for (Object n : savedList) worklist.add(String.valueOf(n));
                Object ex = pending.get("executed");
                executed = ex instanceof Number num ? num.intValue() : 0;
                run.setPendingWorklist(null); // 복원 후 소거 (재중단 시 새로 저장)
                log.info("Cyclic run '{}': resumed from pending_worklist (worklist={}, executed={})",
                        run.getId(), worklist, executed);
            } else {
                // 신규 실행 — 시작 노드: 명시 entry_step → dependsOn 없는 첫 스텝 → steps[0]
                String entry = resolveEntryStep(workflowEntity, steps);
                if (entry == null) {
                    log.warn("Cyclic run '{}': no entry step resolvable", run.getId());
                    completeRun(run, context, "completed");
                    return;
                }
                worklist.add(entry);
                executed = 0;
            }

            // CR-084 P4: stepId 별 cyclic 실행 회차 (이벤트 iterationIndex 용).
            Map<String, Integer> iterationCounts = new HashMap<>();

            while (!worklist.isEmpty()) {
                String stepId = worklist.poll();
                if ("__end__".equals(stepId)) break;

                // 협조적 중지 체크포인트 (cyclic) — 다음 노드 실행 전 감지.
                if (isCancelRequested(run.getId().toString())) {
                    finishCancelled(run, context);
                    return;
                }

                WorkflowStep step = stepMap.get(stepId);
                if (step == null) {
                    log.warn("Cyclic run '{}': step '{}' not found, ending path", run.getId(), stepId);
                    continue;
                }

                // 글로벌 step budget — 무한루프 방어
                if (executed >= budget) {
                    log.warn("Cyclic run '{}': step budget {} exceeded at step '{}'",
                            run.getId(), budget, stepId);
                    run.setStatus("failed");
                    run.setError(Map.of(
                            "reason", "step_budget_exceeded",
                            "budget", budget,
                            "lastStep", stepId));
                    run.setCompletedAt(OffsetDateTime.now());
                    workflowRunRepository.saveAndFlush(run);
                    platformMetrics.recordWorkflowExecution("failed");
                    if (eventPublisher != null) {
                        long d = runDurationMs(run);
                        eventPublisher.runCompleted(run.getId(), run.getParentRunId(), "failed", d);
                    }
                    // CR-090: STEP_FAILED (cyclic step budget)
                    if (eventRecorder != null) {
                        eventRecorder.stepFailed(run.getId(), stepId, runDurationMs(run),
                                "step_budget_exceeded (budget=" + budget + ")", 0);
                    }
                    return;
                }

                // CR-084 P4: 재개 직후 — 이 HUMAN_INPUT 은 resume() 이 이미 승인 결과를
                // stepResults 에 기록했으므로 재중단하지 않고 다음 노드로 진행.
                if (step.type() == WorkflowStep.StepType.HUMAN_INPUT
                        && context.stepResults().containsKey(step.id())) {
                    String next = resolveNextStep(step, asMap(context.stepResults().get(step.id())));
                    if (next != null && !next.isBlank()) worklist.add(next);
                    continue;
                }

                // HUMAN_INPUT — CR-084 P4: worklist+executed 보존 후 일시중단.
                // 재개 시 이 HUMAN_INPUT 스텝부터 이어가도록 맨 앞에 자신을 다시 넣어 저장.
                if (step.type() == WorkflowStep.StepType.HUMAN_INPUT) {
                    log.info("Cyclic run '{}': HUMAN_INPUT at '{}' — pause, persisting worklist",
                            run.getId(), step.id());
                    List<String> remaining = new ArrayList<>();
                    remaining.add(step.id());            // 재개 시 이 스텝부터 (handleHumanInput 결과는 resume 이 stepResults 에 기록)
                    remaining.addAll(worklist);          // 아직 미실행 후속 노드들
                    Map<String, Object> snapshot = new LinkedHashMap<>();
                    snapshot.put("worklist", remaining);
                    snapshot.put("executed", executed);
                    run.setPendingWorklist(snapshot);
                    handleHumanInput(step, run, context); // 내부 save 가 pendingWorklist 포함하여 영속
                    return;
                }

                run.setCurrentStep(step.id());
                workflowRunRepository.save(run);

                // CR-084 P4: 같은 stepId 의 cyclic 회차 (0-based). DAG 경로는 이 코드 미경유.
                int iterationIndex = iterationCounts.merge(step.id(), 1, Integer::sum) - 1;

                long stepStart = System.currentTimeMillis();
                Instant startedAt = Instant.ofEpochMilli(stepStart);
                if (eventPublisher != null) {
                    eventPublisher.stepRunning(run.getId(), run.getParentRunId(), step.id(),
                            startedAt, iterationIndex);
                }
                // CR-090: STEP_START (cyclic)
                if (eventRecorder != null) {
                    eventRecorder.stepStart(run.getId(), step.id(), step.type().name());
                }

                Map<String, Object> result;
                try {
                    result = executeWithRetry(step, context, errorHandling);
                } catch (Exception e) {
                    log.error("Cyclic run '{}': step '{}' failed: {}", run.getId(), step.id(), e.getMessage());
                    long failedAtMs = System.currentTimeMillis();
                    run.setStatus("failed");
                    run.setError(Map.of("step", step.id(), "error", e.getMessage()));
                    run.setCompletedAt(OffsetDateTime.now());
                    workflowRunRepository.saveAndFlush(run);
                    platformMetrics.recordWorkflowExecution("failed");
                    if (eventPublisher != null) {
                        eventPublisher.stepFailed(run.getId(), run.getParentRunId(), step.id(),
                                null, Instant.ofEpochMilli(failedAtMs), e.getMessage());
                        eventPublisher.runCompleted(run.getId(), run.getParentRunId(), "failed", runDurationMs(run));
                    }
                    // CR-090: STEP_FAILED (cyclic)
                    if (eventRecorder != null) {
                        eventRecorder.stepFailed(run.getId(), step.id(),
                                failedAtMs - stepStart, e.getMessage(), errorHandling.retryMaxAttempts() + 1);
                    }
                    return;
                }
                long stepEnd = System.currentTimeMillis();
                Instant completedAt = Instant.ofEpochMilli(stepEnd);
                executed++;

                Map<String, Object> enriched = new LinkedHashMap<>(result);
                enriched.put("_startedAt", startedAt.toString());
                enriched.put("_completedAt", completedAt.toString());
                enriched.put("_durationMs", stepEnd - stepStart);
                context = applyStepResult(step, context, enriched);  // CR-085 P2: 채널 reducer
                run.setStepResults(new LinkedHashMap<>(context.stepResults()));
                workflowRunRepository.save(run);

                if (eventPublisher != null) {
                    Object subRef = step.type() == WorkflowStep.StepType.SUB_WORKFLOW
                            ? result.get("sub_workflow_id") : null;
                    eventPublisher.stepCompleted(run.getId(), run.getParentRunId(), step.id(),
                            startedAt, completedAt,
                            subRef != null ? subRef.toString() : null,
                            previewOutput(result), iterationIndex);
                }
                // CR-090: STEP_END (cyclic) / CR-102: 결과 본문
                if (eventRecorder != null) {
                    eventRecorder.stepEnd(run.getId(), step.id(), stepEnd - stepStart, estimateResultSize(result), resultBody(result));
                }

                // 다음 노드 결정 → worklist push
                String next = resolveNextStep(step, result);
                if (next != null && !next.isBlank()) {
                    worklist.add(next);
                }
                // next 없음 + worklist 비었으면 루프 자연 종료
            }

            completeRun(run, context, "completed");

        } catch (Exception e) {
            log.error("Cyclic run '{}' unexpected error: {}", run.getId(), e.getMessage(), e);
            run.setStatus("failed");
            run.setError(Map.of("error", e.getMessage()));
            run.setCompletedAt(OffsetDateTime.now());
            workflowRunRepository.saveAndFlush(run);
            if (eventPublisher != null) {
                eventPublisher.runCompleted(run.getId(), run.getParentRunId(), "failed", runDurationMs(run));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /** CONDITION/ROUTER 는 next_step, 그 외는 onSuccess 를 그래프 엣지로 사용. */
    String resolveNextStep(WorkflowStep step, Map<String, Object> result) {
        if (step.type() == WorkflowStep.StepType.CONDITION
                || step.type() == WorkflowStep.StepType.ROUTER) {
            Object ns = result.get("next_step");
            return ns instanceof String s ? s : null;
        }
        return step.onSuccess();
    }

    String resolveEntryStep(WorkflowEntity entity, List<WorkflowStep> steps) {
        Object explicit = entity.getTriggerConfig() != null
                ? entity.getTriggerConfig().get("entry_step") : null;
        if (explicit instanceof String s && !s.isBlank()) return s;
        for (WorkflowStep st : steps) {
            if (st.dependsOn() == null || st.dependsOn().isEmpty()) return st.id();
        }
        return steps.isEmpty() ? null : steps.get(0).id();
    }

    int resolveStepBudget(WorkflowEntity entity) {
        // 워크플로우 errorHandling 옆 graph 설정이 아닌, steps 와 무관한 워크플로우 레벨 설정.
        // triggerConfig 내 max_total_steps 키로 override (없으면 기본). 절대 상한으로 clamp.
        Object v = entity.getTriggerConfig() != null
                ? entity.getTriggerConfig().get("max_total_steps") : null;
        int budget = STEP_BUDGET_DEFAULT;
        if (v instanceof Number n) budget = n.intValue();
        if (budget < 1) budget = STEP_BUDGET_DEFAULT;
        return Math.min(budget, STEP_BUDGET_CEILING);
    }

    private long runDurationMs(WorkflowRunEntity run) {
        if (run.getCompletedAt() == null || run.getStartedAt() == null) return 0L;
        return run.getCompletedAt().toInstant().toEpochMilli()
                - run.getStartedAt().toInstant().toEpochMilli();
    }

    private void completeRun(WorkflowRunEntity run, StepContext context, String status) {
        run.setStatus(status);
        run.setCompletedAt(OffsetDateTime.now());
        run.setStepResults(new LinkedHashMap<>(context.stepResults()));
        workflowRunRepository.saveAndFlush(run);
        platformMetrics.recordWorkflowExecution(status);
        if (eventPublisher != null) {
            eventPublisher.runCompleted(run.getId(), run.getParentRunId(), status, runDurationMs(run));
        }
        log.info("Cyclic run '{}' completed: {} step results", run.getId(), context.stepResults().size());
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
