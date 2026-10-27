package com.platform.devwork;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * CR-164: 개발 작업 변경 한 줄이 생겼다(BIZ-129). 이벤트 트리거 워크플로우가 받는다(BIZ-131).
 */
public record DevWorkItemStatusChanged(
        String workItemId,
        String featureName,
        String stage,
        DevWorkStatus status,
        String evidenceUrl,
        OffsetDateTime changedAt,
        UUID changeId,
        DevWorkChangeSource source,
        UUID sourceRunId
) {
    /**
     * 알림 워크플로우 입력. 템플릿이 빈 값을 null 로 풀지 않게 없는 값은 빈 글로 싣는다.
     * reported_at 은 바뀐 시각 그대로라 같은 변경을 다시 보내도 같은 값이다.
     */
    public Map<String, Object> toWorkflowInput() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("work_item_id", workItemId);
        input.put("feature_name", featureName);
        input.put("stage", stage);
        input.put("status", status.name());
        input.put("evidence_url", evidenceUrl != null ? evidenceUrl : "");
        input.put("reported_at", changedAt.withOffsetSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        input.put("change_id", changeId.toString());
        input.put("source", source.name());
        input.put("source_run_id", sourceRunId != null ? sourceRunId.toString() : "");
        return input;
    }
}
