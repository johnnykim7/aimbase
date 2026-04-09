package com.platform.agent;

import com.platform.llm.model.TokenUsage;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * CR-030 PRD-207: 서브에이전트 실행 결과.
 *
 * @param subagentRunId  실행 고유 ID
 * @param sessionId      서브에이전트 세션 ID
 * @param status         최종 상태 (COMPLETED, FAILED, TIMEOUT, CANCELLED)
 * @param output         텍스트 출력
 * @param structuredData 구조화된 결과 (있는 경우)
 * @param exitCode       종료 코드 (0=성공, 1=실패, 2=타임아웃)
 * @param usage          토큰 사용량 (누적)
 * @param worktreePath   worktree 경로 (격리 실행 시, 변경 있을 때만)
 * @param branchName     worktree 브랜치명 (격리 실행 시)
 * @param durationMs     실행 소요 시간 (ms)
 * @param startedAt      시작 시각
 * @param completedAt    완료 시각
 * @param error          에러 메시지 (실패 시)
 */
public record SubagentResult(
        String subagentRunId,
        String sessionId,
        Status status,
        String output,
        Map<String, Object> structuredData,
        int exitCode,
        TokenUsage usage,
        String worktreePath,
        String branchName,
        long durationMs,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        String error
) {
    public enum Status {
        RUNNING,
        COMPLETED,
        FAILED,
        TIMEOUT,
        CANCELLED
    }

    public boolean isSuccess() {
        return status == Status.COMPLETED && exitCode == 0;
    }

    public static SubagentResult running(String subagentRunId, String sessionId) {
        return new SubagentResult(
                subagentRunId, sessionId, Status.RUNNING,
                null, null, -1, null, null, null,
                0, OffsetDateTime.now(), null, null
        );
    }

    public static SubagentResult completed(String subagentRunId, String sessionId,
                                           String output, Map<String, Object> structuredData,
                                           TokenUsage usage, long durationMs,
                                           OffsetDateTime startedAt,
                                           String worktreePath, String branchName) {
        return new SubagentResult(
                subagentRunId, sessionId, Status.COMPLETED,
                output, structuredData, 0, usage,
                worktreePath, branchName,
                durationMs, startedAt, OffsetDateTime.now(), null
        );
    }

    public static SubagentResult failed(String subagentRunId, String sessionId,
                                        String error, long durationMs,
                                        OffsetDateTime startedAt) {
        return new SubagentResult(
                subagentRunId, sessionId, Status.FAILED,
                null, null, 1, null, null, null,
                durationMs, startedAt, OffsetDateTime.now(), error
        );
    }

    public static SubagentResult timeout(String subagentRunId, String sessionId,
                                         long durationMs, OffsetDateTime startedAt) {
        return new SubagentResult(
                subagentRunId, sessionId, Status.TIMEOUT,
                null, null, 2, null, null, null,
                durationMs, startedAt, OffsetDateTime.now(), "Subagent execution timed out"
        );
    }
}
