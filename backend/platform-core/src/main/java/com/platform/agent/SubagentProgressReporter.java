package com.platform.agent;

import com.platform.domain.ToolExecutionLogEntity;
import com.platform.repository.SubagentRunRepository;
import com.platform.repository.ToolExecutionLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * CR-031 PRD-216: Agent 진행 요약 — 30초 주기 갱신.
 *
 * SubagentRunner.run() 실행 중 30초 주기로 최근 도구 호출 기록 기반
 * 3~5단어 현재형 요약을 생성하여 SubagentRunEntity.progressSummary에 갱신한다.
 */
@Component
public class SubagentProgressReporter {

    private static final Logger log = LoggerFactory.getLogger(SubagentProgressReporter.class);
    private static final long REPORT_INTERVAL_SECONDS = 30;

    private final SubagentRunRepository subagentRunRepository;
    private final ToolExecutionLogRepository toolExecutionLogRepository;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1,
            Thread.ofVirtual().name("progress-reporter-", 0).factory());

    // runId → ScheduledFuture (실행 중인 리포터 추적)
    private final Map<UUID, ScheduledFuture<?>> activeReporters = new ConcurrentHashMap<>();

    public SubagentProgressReporter(SubagentRunRepository subagentRunRepository,
                                     ToolExecutionLogRepository toolExecutionLogRepository) {
        this.subagentRunRepository = subagentRunRepository;
        this.toolExecutionLogRepository = toolExecutionLogRepository;
    }

    /**
     * 에이전트 진행 보고를 시작한다. 30초 주기.
     */
    public void start(UUID runId, String childSessionId) {
        if (runId == null || childSessionId == null) return;

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            try {
                updateProgress(runId, childSessionId);
            } catch (Exception e) {
                log.debug("진행 요약 갱신 실패 (무시): runId={}, error={}", runId, e.getMessage());
            }
        }, REPORT_INTERVAL_SECONDS, REPORT_INTERVAL_SECONDS, TimeUnit.SECONDS);

        activeReporters.put(runId, future);
        log.debug("진행 보고 시작: runId={}, interval={}s", runId, REPORT_INTERVAL_SECONDS);
    }

    /**
     * 에이전트 완료 시 진행 보고를 중지한다.
     */
    public void stop(UUID runId) {
        ScheduledFuture<?> future = activeReporters.remove(runId);
        if (future != null) {
            future.cancel(false);
            log.debug("진행 보고 중지: runId={}", runId);
        }
    }

    /**
     * 최근 도구 호출 기록 기반 진행 요약 생성 + DB 갱신.
     * LLM 호출 없이 도구명+입력 기반 간단 요약.
     */
    void updateProgress(UUID runId, String childSessionId) {
        List<ToolExecutionLogEntity> recentLogs =
                toolExecutionLogRepository.findBySessionIdOrderByCreatedAtAsc(childSessionId);

        if (recentLogs.isEmpty()) return;

        // 최근 3개 도구 호출에서 요약 생성
        int start = Math.max(0, recentLogs.size() - 3);
        StringBuilder summary = new StringBuilder();
        for (int i = start; i < recentLogs.size(); i++) {
            ToolExecutionLogEntity logEntry = recentLogs.get(i);
            if (summary.length() > 0) summary.append(" → ");
            summary.append(logEntry.getToolName());
            if (logEntry.getInputSummary() != null && !logEntry.getInputSummary().isBlank()) {
                String input = logEntry.getInputSummary();
                if (input.length() > 50) input = input.substring(0, 50) + "...";
                summary.append("(").append(input).append(")");
            }
        }

        String raw = summary.toString();
        final String progressText = raw.length() > 500 ? raw.substring(0, 497) + "..." : raw;

        subagentRunRepository.findById(runId).ifPresent(entity -> {
            entity.setProgressSummary(progressText);
            subagentRunRepository.save(entity);
            log.debug("진행 요약 갱신: runId={}, summary={}", runId, progressText);
        });
    }
}
