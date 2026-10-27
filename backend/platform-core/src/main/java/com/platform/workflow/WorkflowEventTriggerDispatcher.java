package com.platform.workflow;

import com.platform.devwork.DevWorkItemStatusChanged;
import com.platform.domain.WorkflowEntity;
import com.platform.domain.WorkflowRunEntity;
import com.platform.repository.WorkflowRepository;
import com.platform.workflow.model.WorkflowTriggerEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * CR-164: 이벤트 트리거를 단 활성 워크플로우를 그 이벤트마다 실행한다(PRD-352, BIZ-131).
 *
 * <p>이벤트를 낸 쪽(상태 기록)과 다른 워크플로우가 한 워크플로우의 실패에 끌려가지 않게 예외를 밖으로 내지 않는다.
 * 실행은 엔진이 비동기로 하므로 여기서는 시작만 한다.
 */
@Component
public class WorkflowEventTriggerDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEventTriggerDispatcher.class);

    private final WorkflowRepository workflowRepository;
    private final WorkflowEngine workflowEngine;

    public WorkflowEventTriggerDispatcher(WorkflowRepository workflowRepository, WorkflowEngine workflowEngine) {
        this.workflowRepository = workflowRepository;
        this.workflowEngine = workflowEngine;
    }

    @EventListener
    public void onDevWorkItemStatusChanged(DevWorkItemStatusChanged event) {
        dispatch(WorkflowTriggerEvent.DEV_WORK_ITEM_STATUS_CHANGED, event.toWorkflowInput());
    }

    void dispatch(WorkflowTriggerEvent event, Map<String, Object> input) {
        List<WorkflowEntity> targets;
        try {
            targets = workflowRepository.findActiveByTrigger(WorkflowTriggerEvent.TRIGGER_TYPE, event.name());
        } catch (RuntimeException e) {
            log.error("CR-164 {} 트리거 워크플로우를 찾지 못함: {}", event, e.getMessage(), e);
            return;
        }
        for (WorkflowEntity workflow : targets) {
            try {
                WorkflowRunEntity run = workflowEngine.execute(workflow.getId(), input, null);
                log.info("CR-164 {} 트리거로 워크플로우 '{}' 실행 {}", event, workflow.getId(), run.getId());
            } catch (RuntimeException e) {
                log.error("CR-164 {} 트리거 워크플로우 '{}' 실행 실패: {}", event, workflow.getId(), e.getMessage(), e);
            }
        }
    }
}
