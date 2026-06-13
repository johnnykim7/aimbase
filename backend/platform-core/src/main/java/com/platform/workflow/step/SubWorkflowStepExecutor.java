package com.platform.workflow.step;

import com.platform.domain.master.PlatformWorkflowEntity;
import com.platform.repository.master.PlatformWorkflowRepository;
import com.platform.workflow.StepContext;
import com.platform.workflow.WorkflowEngine;
import com.platform.workflow.model.WorkflowStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

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
    private final ApplicationContext applicationContext;

    public SubWorkflowStepExecutor(PlatformWorkflowRepository platformWorkflowRepository,
                                    ApplicationContext applicationContext) {
        this.platformWorkflowRepository = platformWorkflowRepository;
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

        log.info("SUB_WORKFLOW step '{}': delegating platform workflow '{}' ({}) to child run",
                step.id(), workflowId, platform.getName());

        // CR-065: 인라인 실행 대신 WorkflowEngine 에 자식 run 동기 실행 위임.
        // ApplicationContext 지연 로드로 순환의존 회피 (ParallelStepExecutor 와 동일 패턴).
        UUID parentRunId = parseUuid(parentContext.workflowRunId());
        WorkflowEngine engine = applicationContext.getBean(WorkflowEngine.class);
        return engine.executeSubWorkflowSync(
                platform, subInput, parentRunId, step.id(), parentContext.sessionId());
    }

    // ─── 내부 헬퍼 ──────────────────────────────────────────────────────────

    /** workflowRunId 가 UUID 가 아니면(테스트 등) null 반환 — parent_run_id 미설정 자식으로 처리. */
    private UUID parseUuid(String runId) {
        if (runId == null || runId.isBlank()) return null;
        try {
            return UUID.fromString(runId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
