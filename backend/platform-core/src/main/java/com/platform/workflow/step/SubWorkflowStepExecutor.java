package com.platform.workflow.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.domain.master.PlatformWorkflowEntity;
import com.platform.repository.master.PlatformWorkflowRepository;
import com.platform.workflow.StepContext;
import com.platform.workflow.model.ErrorHandling;
import com.platform.workflow.model.WorkflowStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * SUB_WORKFLOW 스텝 실행기.
 *
 * 플랫폼 공용 워크플로우를 서브 워크플로우로 실행.
 * 부모 워크플로우의 DAG 안에서 하나의 노드로 표현되며,
 * 내부적으로 공용 워크플로우의 전체 steps를 순차 실행한 뒤
 * 마지막 스텝의 output을 이 노드의 output으로 반환.
 *
 * config 형식:
 * {
 *   "workflow_id": "file-analysis",           // 공용 워크플로우 ID
 *   "input": {                                // 서브 워크플로우 입력 (변수 치환 지원)
 *     "zip_path": "{{input.zip_path}}",
 *     "prompt": "코드 리뷰해줘"
 *   }
 * }
 */
@Component
public class SubWorkflowStepExecutor implements StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(SubWorkflowStepExecutor.class);

    private final PlatformWorkflowRepository platformWorkflowRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationContext applicationContext;

    public SubWorkflowStepExecutor(PlatformWorkflowRepository platformWorkflowRepository,
                                    ObjectMapper objectMapper,
                                    ApplicationContext applicationContext) {
        this.platformWorkflowRepository = platformWorkflowRepository;
        this.objectMapper = objectMapper;
        this.applicationContext = applicationContext;
    }

    @Override
    public WorkflowStep.StepType supports() {
        return WorkflowStep.StepType.SUB_WORKFLOW;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(WorkflowStep step, StepContext parentContext) {
        Map<String, Object> config = step.config();
        String workflowId = (String) config.get("workflow_id");
        if (workflowId == null || workflowId.isBlank()) {
            throw new IllegalArgumentException(
                    "SUB_WORKFLOW step '" + step.id() + "' missing 'workflow_id' config");
        }

        PlatformWorkflowEntity platform = platformWorkflowRepository.findById(workflowId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Platform workflow not found: " + workflowId));

        if (!platform.isActive()) {
            throw new IllegalStateException(
                    "Platform workflow is not active: " + workflowId);
        }

        // 서브 워크플로우 입력 구성: 부모 컨텍스트에서 변수 치환
        Object inputObj = config.get("input");
        Map<String, Object> subInput = inputObj instanceof Map
                ? parentContext.resolveMap((Map<String, Object>) inputObj)
                : Map.of();

        log.info("SUB_WORKFLOW step '{}': executing platform workflow '{}' ({})",
                step.id(), workflowId, platform.getName());

        // 서브 워크플로우의 steps 파싱
        List<WorkflowStep> subSteps = parseSteps(platform);
        if (subSteps.isEmpty()) {
            log.warn("Platform workflow '{}' has no steps", workflowId);
            return Map.of("output", "", "sub_workflow_id", workflowId);
        }

        ErrorHandling errorHandling = parseErrorHandling(platform);

        // 서브 컨텍스트 생성 (서브 워크플로우 입력을 inputData로 설정)
        StepContext subContext = new StepContext(
                parentContext.workflowRunId(),
                "platform:" + workflowId,
                parentContext.sessionId(),
                subInput,
                new LinkedHashMap<>()
        );

        // 위상 정렬 후 순차 실행
        List<WorkflowStep> sorted = topologicalSort(subSteps);
        Map<String, Object> lastResult = Map.of("output", "");

        // WorkflowEngine의 StepExecutor들을 가져와서 실행
        Map<WorkflowStep.StepType, StepExecutor> executors = getExecutors();

        for (WorkflowStep subStep : sorted) {
            StepExecutor executor = executors.get(subStep.type());
            if (executor == null) {
                throw new IllegalStateException(
                        "No executor for step type: " + subStep.type());
            }

            log.debug("SUB_WORKFLOW '{}' → executing sub-step '{}' (type={})",
                    step.id(), subStep.id(), subStep.type());

            lastResult = executeWithRetry(executor, subStep, subContext, errorHandling);
            subContext = subContext.withStepResult(subStep.id(), lastResult);
        }

        log.info("SUB_WORKFLOW step '{}': completed {} sub-steps", step.id(), sorted.size());

        // 마지막 스텝의 결과를 이 노드의 output으로 반환
        Map<String, Object> result = new LinkedHashMap<>(lastResult);
        result.put("sub_workflow_id", workflowId);
        return result;
    }

    // ─── 내부 헬퍼 ──────────────────────────────────────────────────────────

    private Map<WorkflowStep.StepType, StepExecutor> getExecutors() {
        return applicationContext.getBeansOfType(StepExecutor.class).values().stream()
                .collect(Collectors.toMap(StepExecutor::supports, e -> e));
    }

    private List<WorkflowStep> parseSteps(PlatformWorkflowEntity entity) {
        List<Map<String, Object>> rawSteps = entity.getSteps();
        if (rawSteps == null || rawSteps.isEmpty()) return List.of();
        return rawSteps.stream()
                .map(raw -> objectMapper.convertValue(raw, WorkflowStep.class))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private ErrorHandling parseErrorHandling(PlatformWorkflowEntity entity) {
        Map<String, Object> raw = entity.getErrorHandling();
        if (raw == null) return ErrorHandling.defaults();
        try {
            return objectMapper.convertValue(raw, ErrorHandling.class);
        } catch (Exception e) {
            return ErrorHandling.defaults();
        }
    }

    private Map<String, Object> executeWithRetry(StepExecutor executor, WorkflowStep step,
                                                   StepContext context, ErrorHandling errorHandling) {
        int maxAttempts = Math.max(1, errorHandling.retryMaxAttempts() + 1);
        long delayMs = errorHandling.retryDelayMs();
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return executor.execute(step, context);
            } catch (Exception e) {
                lastException = e;
                if (attempt < maxAttempts) {
                    log.warn("Sub-step '{}' attempt {}/{} failed: {}. Retrying in {}ms...",
                            step.id(), attempt, maxAttempts, e.getMessage(), delayMs);
                    try { Thread.sleep(delayMs); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during retry", ie);
                    }
                }
            }
        }
        throw new RuntimeException(
                "Sub-step '" + step.id() + "' failed after " + maxAttempts + " attempt(s): "
                        + lastException.getMessage(), lastException);
    }

    private List<WorkflowStep> topologicalSort(List<WorkflowStep> steps) {
        Map<String, WorkflowStep> stepById = new LinkedHashMap<>();
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();

        for (WorkflowStep s : steps) {
            stepById.put(s.id(), s);
            inDegree.putIfAbsent(s.id(), 0);
        }
        for (WorkflowStep s : steps) {
            if (s.dependsOn() != null) {
                for (String dep : s.dependsOn()) {
                    inDegree.merge(s.id(), 1, Integer::sum);
                    dependents.computeIfAbsent(dep, k -> new ArrayList<>()).add(s.id());
                }
            }
        }

        Queue<String> queue = new LinkedList<>();
        inDegree.forEach((id, deg) -> { if (deg == 0) queue.add(id); });

        List<WorkflowStep> sorted = new ArrayList<>(steps.size());
        while (!queue.isEmpty()) {
            String id = queue.poll();
            WorkflowStep s = stepById.get(id);
            if (s != null) sorted.add(s);
            for (String dep : dependents.getOrDefault(id, List.of())) {
                if (inDegree.merge(dep, -1, Integer::sum) == 0) queue.add(dep);
            }
        }
        return sorted.size() == steps.size() ? sorted : new ArrayList<>(steps);
    }
}
