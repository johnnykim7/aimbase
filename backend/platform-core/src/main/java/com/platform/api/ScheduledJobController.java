package com.platform.api;

import com.platform.domain.master.ScheduledJobEntity;
import com.platform.tenant.TenantContext;
import com.platform.workflow.CronScheduleManager;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * CR-035 PRD-234~235: Cron 스케줄 관리 REST API.
 */
@RestController
@RequestMapping("/api/v1/scheduled-jobs")
public class ScheduledJobController {

    private final CronScheduleManager cronScheduleManager;

    public ScheduledJobController(CronScheduleManager cronScheduleManager) {
        this.cronScheduleManager = cronScheduleManager;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        String tenantId = TenantContext.getTenantId();
        List<Map<String, Object>> jobs = cronScheduleManager.listJobs(tenantId).stream()
                .map(ScheduledJobEntity::toMap)
                .toList();
        return ApiResponse.ok(jobs);
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        String tenantId = TenantContext.getTenantId();
        String name = (String) body.get("name");
        String cron = (String) body.get("cron_expression");
        String targetType = (String) body.get("target_type");
        String targetId = (String) body.get("target_id");
        @SuppressWarnings("unchecked")
        Map<String, Object> input = (Map<String, Object>) body.get("input");

        try {
            ScheduledJobEntity job = cronScheduleManager.createJob(name, cron, targetType, targetId, input, tenantId);
            return ApiResponse.ok(job.toMap());
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @DeleteMapping("/{jobId}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable String jobId) {
        try {
            cronScheduleManager.deleteJob(jobId);
            return ApiResponse.ok(Map.of("deleted", jobId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @PatchMapping("/{jobId}/toggle")
    public ApiResponse<Map<String, Object>> toggle(@PathVariable String jobId,
                                                    @RequestBody Map<String, Object> body) {
        boolean active = Boolean.TRUE.equals(body.get("is_active"));
        try {
            ScheduledJobEntity job = cronScheduleManager.toggleActive(jobId, active);
            return ApiResponse.ok(job.toMap());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(e.getMessage());
        }
    }
}
