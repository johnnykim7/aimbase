package com.platform.devwork;

import com.platform.domain.WorkflowRunEntity;
import com.platform.repository.WorkflowRunRepository;
import com.platform.workflow.event.WorkflowEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * CR-164: 실행 입력 {@code _dev_work_item} 을 실은 워크플로우 run 을 개발 작업에 잇는다(PRD-351, BIZ-130).
 *
 * <p>실패를 단계로 보고하면 실패한 run 의 뒷단계가 돌지 않아 못 보낸다. 그래서 시작은 엔진이 run 을 만들 때,
 * 끝은 모든 종료 경로가 내는 {@link WorkflowEvents.RunCompleted} 로 받는다.
 * 부모가 있는 자식 run 은 부모가 이미 잇고 있으므로 잇지 않는다.
 */
@Component
public class DevWorkItemRunBinding {

    private static final Logger log = LoggerFactory.getLogger(DevWorkItemRunBinding.class);

    public static final String INPUT_KEY = "_dev_work_item";

    private final DevWorkItemService service;
    private final WorkflowRunRepository runs;

    public DevWorkItemRunBinding(DevWorkItemService service, WorkflowRunRepository runs) {
        this.service = service;
        this.runs = runs;
    }

    record Binding(String workItemId, String featureName, String stage, String evidenceUrl) {}

    /** run 을 만들기 전에 부른다. 칸이 틀리면 run 을 만들지 않게 던진다. */
    public void validate(Map<String, Object> input) {
        parse(input);
    }

    /** 엔진이 run 을 저장한 뒤, 실행 스레드를 시작하기 전에 부른다. 그래야 끝 상태보다 먼저 남는다. */
    public void onRunStarted(WorkflowRunEntity run) {
        if (run.getParentRunId() != null) {
            return;
        }
        report(run, DevWorkStatus.IN_PROGRESS);
    }

    @EventListener
    public void onRunCompleted(WorkflowEvents.RunCompleted event) {
        if (event.parentRunId() != null) {
            return;
        }
        DevWorkStatus status = switch (event.status()) {
            case "completed" -> DevWorkStatus.COMPLETED;
            case "failed" -> DevWorkStatus.FAILED;
            case "cancelled" -> DevWorkStatus.CANCELLED;
            default -> null;
        };
        if (status == null) {
            return;
        }
        // 같은 이벤트를 받는 실행 구독(SSE)이 뒤에 있으니 여기서 예외를 밖으로 내지 않는다
        try {
            runs.findById(event.runId()).ifPresent(run -> report(run, status));
        } catch (RuntimeException e) {
            log.error("CR-164 run {} 종료를 개발 작업에 남기지 못함: {}", event.runId(), e.getMessage(), e);
        }
    }

    private void report(WorkflowRunEntity run, DevWorkStatus status) {
        Binding binding;
        try {
            binding = parse(run.getInputData());
        } catch (IllegalArgumentException e) {
            log.warn("CR-164 run {} 의 {} 가 틀려 잇지 않음: {}", run.getId(), INPUT_KEY, e.getMessage());
            return;
        }
        if (binding == null) {
            return;
        }
        try {
            service.report(new DevWorkItemService.Report(binding.workItemId(), binding.featureName(),
                    binding.stage(), status, binding.evidenceUrl()), DevWorkChangeSource.WORKFLOW_RUN, run.getId());
        } catch (RuntimeException e) {
            log.error("CR-164 run {} 의 개발 작업 {} 상태 {} 를 남기지 못함: {}",
                    run.getId(), binding.workItemId(), status, e.getMessage(), e);
        }
    }

    static Binding parse(Map<String, Object> input) {
        if (input == null || !input.containsKey(INPUT_KEY)) {
            return null;
        }
        if (!(input.get(INPUT_KEY) instanceof Map<?, ?> raw)) {
            throw DevWorkItemService.invalid(INPUT_KEY);
        }
        return new Binding(
                DevWorkItemService.requireText(text(raw.get("work_item_id")), "work_item_id", DevWorkItemService.MAX_WORK_ITEM_ID),
                DevWorkItemService.requireText(text(raw.get("feature_name")), "feature_name", DevWorkItemService.MAX_FEATURE_NAME),
                DevWorkItemService.requireText(text(raw.get("stage")), "stage", DevWorkItemService.MAX_STAGE),
                DevWorkItemService.normalizeEvidenceUrl(text(raw.get("evidence_url"))));
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
