package com.platform.repository;

import com.platform.domain.ToolExecutionLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * CR-029: 도구 실행 이력 저장소.
 */
@Repository
public interface ToolExecutionLogRepository extends JpaRepository<ToolExecutionLogEntity, UUID> {

    List<ToolExecutionLogEntity> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    List<ToolExecutionLogEntity> findByWorkflowRunIdOrderByCreatedAtAsc(String workflowRunId);

    List<ToolExecutionLogEntity> findBySessionIdAndTurnNumberOrderBySequenceInTurnAsc(
            String sessionId, Integer turnNumber);

    Page<ToolExecutionLogEntity> findBySessionId(String sessionId, Pageable pageable);

    Page<ToolExecutionLogEntity> findByToolName(String toolName, Pageable pageable);

    /**
     * CR-031 PRD-211: Post-Compact Recovery — 최근 파일 참조 도구 실행 이력 조회.
     * file_read, glob, grep 등 파일 참조 도구의 최근 실행을 중복 없이 반환한다.
     */
    @Query(value = """
            SELECT DISTINCT ON (t.input_summary) t.*
            FROM tool_execution_log t
            WHERE t.session_id = :sessionId
              AND t.tool_name IN ('file_read', 'glob', 'grep', 'document_section_read', 'workspace_snapshot')
              AND t.success = true
            ORDER BY t.input_summary, t.created_at DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<ToolExecutionLogEntity> findRecentFileReferences(
            @Param("sessionId") String sessionId,
            @Param("limit") int limit);
}
