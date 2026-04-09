package com.platform.workflow;

import com.platform.domain.master.ScheduledJobEntity;
import com.platform.llm.model.ToolCall;
import com.platform.monitoring.PlatformMetrics;
import com.platform.policy.AuditLogger;
import com.platform.repository.master.ScheduledJobRepository;
import com.platform.tenant.TenantContext;
import com.platform.tool.ToolContext;
import com.platform.tool.ToolRegistry;
import com.platform.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * CR-035 PRD-228: Cron 스케줄 엔진.
 *
 * Spring TaskScheduler를 래핑하여 워크플로우/도구를 cron 표현식 기반으로 자동 실행한다.
 * 서버 기동 시 active job을 DB에서 로드하여 스케줄러에 등록.
 *
 * BIZ-057: 테넌트당 최대 50개 스케줄.
 * BIZ-058: cron 최소 간격 1분.
 * BIZ-059: 3회 연속 실패 시 비활성화.
 */
@Component
public class CronScheduleManager {

    private static final Logger log = LoggerFactory.getLogger(CronScheduleManager.class);
    private static final int MAX_JOBS_PER_TENANT = 50;
    private static final int MAX_FAILURE_COUNT = 3;

    private final ScheduledJobRepository jobRepository;
    private final TaskScheduler taskScheduler;
    private final WorkflowEngine workflowEngine;
    private final ToolRegistry toolRegistry;
    private final AuditLogger auditLogger;
    private final PlatformMetrics platformMetrics;

    private final Map<String, ScheduledFuture<?>> activeFutures = new ConcurrentHashMap<>();

    public CronScheduleManager(ScheduledJobRepository jobRepository,
                               TaskScheduler taskScheduler,
                               @org.springframework.context.annotation.Lazy WorkflowEngine workflowEngine,
                               @org.springframework.context.annotation.Lazy ToolRegistry toolRegistry,
                               AuditLogger auditLogger,
                               PlatformMetrics platformMetrics) {
        this.jobRepository = jobRepository;
        this.taskScheduler = taskScheduler;
        this.workflowEngine = workflowEngine;
        this.toolRegistry = toolRegistry;
        this.auditLogger = auditLogger;
        this.platformMetrics = platformMetrics;
    }

    /** 서버 기동 시 active job 전부 로드 → 스케줄러 등록 */
    @PostConstruct
    public void loadActiveJobs() {
        var activeJobs = jobRepository.findByIsActiveTrueOrderByCreatedAtDesc();
        for (var job : activeJobs) {
            scheduleJob(job);
        }
        log.info("CronScheduleManager loaded {} active job(s)", activeJobs.size());
    }

    /**
     * 새 스케줄 작업 등록.
     * @return 생성된 ScheduledJobEntity
     * @throws IllegalStateException 테넌트 쿼터 초과 시
     * @throws IllegalArgumentException 잘못된 cron 표현식
     */
    public ScheduledJobEntity createJob(String name, String cronExpression,
                                         String targetType, String targetId,
                                         Map<String, Object> inputPayload,
                                         String tenantId) {
        // BIZ-057: 테넌트당 최대 50개
        long count = jobRepository.countByTenantId(tenantId);
        if (count >= MAX_JOBS_PER_TENANT) {
            throw new IllegalStateException(
                    "테넌트당 최대 " + MAX_JOBS_PER_TENANT + "개 스케줄 제한 초과 (현재: " + count + ")");
        }

        // BIZ-058: cron 유효성 검증
        validateCronExpression(cronExpression);

        var job = new ScheduledJobEntity();
        job.setId("sj-" + UUID.randomUUID().toString().substring(0, 8));
        job.setName(name);
        job.setCronExpression(cronExpression);
        job.setTargetType(targetType.toUpperCase());
        job.setTargetId(targetId);
        job.setInputPayload(inputPayload != null ? inputPayload : Map.of());
        job.setTenantId(tenantId);
        job.setActive(true);

        job = jobRepository.save(job);
        scheduleJob(job);

        auditLogger.log("CRON_JOB_CREATED", job.getId(), null, null,
                Map.of("jobId", job.getId(), "name", name, "cron", cronExpression, "tenantId", tenantId), null);
        log.info("Created scheduled job: {} [{}] for tenant {}", name, cronExpression, tenantId);

        return job;
    }

    /** 스케줄 작업 삭제 */
    public void deleteJob(String jobId) {
        var job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 스케줄: " + jobId));

        cancelJob(jobId);
        jobRepository.delete(job);

        auditLogger.log("CRON_JOB_DELETED", jobId, null, null,
                Map.of("jobId", jobId, "name", job.getName(), "tenantId", job.getTenantId()), null);
        log.info("Deleted scheduled job: {}", jobId);
    }

    /** 테넌트의 모든 스케줄 목록 조회 */
    public java.util.List<ScheduledJobEntity> listJobs(String tenantId) {
        return jobRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    /** 활성/비활성 토글 */
    public ScheduledJobEntity toggleActive(String jobId, boolean active) {
        var job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 스케줄: " + jobId));

        job.setActive(active);
        if (active) {
            job.setFailureCount(0);
            scheduleJob(job);
        } else {
            cancelJob(jobId);
        }
        return jobRepository.save(job);
    }

    // --- Private ---

    private void scheduleJob(ScheduledJobEntity job) {
        cancelJob(job.getId()); // 기존 future가 있으면 제거

        try {
            var trigger = new CronTrigger(job.getCronExpression(), ZoneId.of("Asia/Seoul"));
            ScheduledFuture<?> future = taskScheduler.schedule(
                    () -> executeJob(job.getId()), trigger);
            activeFutures.put(job.getId(), future);
        } catch (IllegalArgumentException e) {
            log.error("Invalid cron for job {}: {}", job.getId(), e.getMessage());
        }
    }

    private void cancelJob(String jobId) {
        var future = activeFutures.remove(jobId);
        if (future != null) {
            future.cancel(false);
        }
    }

    private void executeJob(String jobId) {
        var jobOpt = jobRepository.findById(jobId);
        if (jobOpt.isEmpty()) {
            cancelJob(jobId);
            return;
        }

        var job = jobOpt.get();
        if (!job.isActive()) {
            cancelJob(jobId);
            return;
        }

        log.info("Executing scheduled job: {} [{}]", job.getName(), job.getId());
        job.setLastRunAt(OffsetDateTime.now());
        job.setLastRunStatus("RUNNING");
        jobRepository.save(job);

        try {
            TenantContext.setTenantId(job.getTenantId());

            if ("WORKFLOW".equals(job.getTargetType())) {
                workflowEngine.execute(job.getTargetId(), job.getInputPayload(), null);
            } else if ("TOOL".equals(job.getTargetType())) {
                var call = new ToolCall("call-cron-" + jobId, job.getTargetId(), job.getInputPayload());
                var ctx = ToolContext.minimal(job.getTenantId(), null);
                ToolResult result = toolRegistry.execute(call, ctx);
                if (!result.success()) {
                    throw new RuntimeException("Tool execution failed: " + result.summary());
                }
            }

            job.setLastRunStatus("SUCCESS");
            job.setFailureCount(0);
            platformMetrics.recordToolExecution("cron:" + job.getTargetId(), true);

        } catch (Exception e) {
            log.error("Scheduled job {} failed: {}", jobId, e.getMessage());
            job.setLastRunStatus("FAILED");
            job.setFailureCount(job.getFailureCount() + 1);
            platformMetrics.recordToolExecution("cron:" + job.getTargetId(), false);

            // BIZ-059: 3회 실패 시 비활성화
            if (job.getFailureCount() >= MAX_FAILURE_COUNT) {
                job.setActive(false);
                cancelJob(jobId);
                log.warn("Job {} deactivated after {} consecutive failures", jobId, MAX_FAILURE_COUNT);
                auditLogger.log("CRON_JOB_DEACTIVATED", jobId, null, null,
                        Map.of("jobId", jobId, "reason", "max_failures_exceeded", "tenantId", job.getTenantId()), null);
            }
        } finally {
            TenantContext.clear();
            jobRepository.save(job);
        }
    }

    private void validateCronExpression(String cron) {
        try {
            new CronTrigger(cron);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("잘못된 cron 표현식: " + cron + " — " + e.getMessage());
        }

        // BIZ-058: 초 단위 (* 또는 숫자만)로 매초 실행 방지 — 최소 1분 간격
        // Spring cron: "초 분 시 일 월 요일"
        String[] parts = cron.trim().split("\\s+");
        if (parts.length >= 2) {
            String secondField = parts[0];
            String minuteField = parts[1];
            // 매초 + 매분이면 거부
            if ("*".equals(secondField) && "*".equals(minuteField)) {
                throw new IllegalArgumentException("BIZ-058: cron 최소 간격은 1분입니다");
            }
        }
    }
}
