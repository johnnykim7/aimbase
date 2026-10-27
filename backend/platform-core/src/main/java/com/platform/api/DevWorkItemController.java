package com.platform.api;

import com.platform.devwork.DevWorkChangeSource;
import com.platform.devwork.DevWorkItemService;
import com.platform.domain.DevWorkItemChangeEntity;
import com.platform.domain.DevWorkItemEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/dev-work-items")
@Tag(name = "Dev Work Items", description = "개발 작업 상태 원천 (CR-164)")
public class DevWorkItemController {

    private static final int MAX_PAGE_SIZE = 100;

    private final DevWorkItemService service;

    public DevWorkItemController(DevWorkItemService service) {
        this.service = service;
    }

    @PostMapping("/{work_item_id}/status")
    @Operation(summary = "개발 작업 상태 보고", description = "없는 작업이면 만든다. 바뀐 것이 없으면 change 는 null 이고 알림도 없다.")
    public ApiResponse<Map<String, Object>> report(@PathVariable("work_item_id") String workItemId,
                                                   @RequestBody Map<String, Object> body) {
        DevWorkItemService.Outcome outcome = service.report(new DevWorkItemService.Report(
                workItemId,
                text(body.get("feature_name")),
                text(body.get("stage")),
                DevWorkItemService.parseStatus(body.get("status")),
                text(body.get("evidence_url"))), DevWorkChangeSource.API, null);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("item", toMap(outcome.item()));
        data.put("change", outcome.change() != null ? toMap(outcome.change()) : null);
        return ApiResponse.ok(data);
    }

    @GetMapping
    @Operation(summary = "개발 작업 목록", description = "상태가 최근에 바뀐 순")
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        return ApiResponse.page(service.list(pageable).map(DevWorkItemController::toMap));
    }

    @GetMapping("/{work_item_id}")
    @Operation(summary = "개발 작업 상세", description = "작업과 최근 변경 100줄")
    public ApiResponse<Map<String, Object>> get(@PathVariable("work_item_id") String workItemId) {
        DevWorkItemEntity item = service.find(workItemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Dev work item not found: " + workItemId));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("item", toMap(item));
        data.put("changes", service.recentChanges(workItemId).stream().map(DevWorkItemController::toMap).toList());
        return ApiResponse.ok(data);
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static Map<String, Object> toMap(DevWorkItemEntity item) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("work_item_id", item.getWorkItemId());
        m.put("feature_name", item.getFeatureName());
        m.put("stage", item.getStage());
        m.put("status", item.getStatus().name());
        m.put("evidence_url", item.getEvidenceUrl());
        m.put("status_changed_at", utc(item.getStatusChangedAt()));
        m.put("last_workflow_run_id", item.getLastWorkflowRunId());
        m.put("created_at", utc(item.getCreatedAt()));
        m.put("updated_at", utc(item.getUpdatedAt()));
        return m;
    }

    private static Map<String, Object> toMap(DevWorkItemChangeEntity change) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", change.getId());
        m.put("work_item_id", change.getWorkItemId());
        m.put("stage", change.getStage());
        m.put("status", change.getStatus().name());
        m.put("evidence_url", change.getEvidenceUrl());
        m.put("changed_at", utc(change.getChangedAt()));
        m.put("source", change.getSource().name());
        m.put("workflow_run_id", change.getWorkflowRunId());
        return m;
    }

    private static OffsetDateTime utc(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
    }
}
