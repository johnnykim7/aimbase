package com.platform.repository;

import com.platform.domain.PendingApprovalEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Repository
public interface PendingApprovalRepository extends JpaRepository<PendingApprovalEntity, UUID> {
    Page<PendingApprovalEntity> findByStatusOrderByRequestedAtDesc(String status, Pageable pageable);
    List<PendingApprovalEntity> findByActionLogId(UUID actionLogId);

    /** CR-174 BIZ-144: 승인 시한이 지난 대기. 감시기가 timeout 으로 닫는다. */
    List<PendingApprovalEntity> findByStatusAndApprovalChannelAndTimeoutAtBefore(
            String status, String approvalChannel, java.time.OffsetDateTime before);

    /**
     * CR-185 BIZ-245: 대기일 때만 닫는다. 둘이 함께 정해도 한 쪽만 1 을 받는다.
     * 0 이면 이미 정해졌거나 마감, 중지된 줄이다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE PendingApprovalEntity a SET a.status = :status, a.approvedBy = :decidedBy, "
            + "a.reason = COALESCE(:reason, a.reason), a.resolvedAt = :at "
            + "WHERE a.id = :id AND a.status = 'pending'")
    int closeIfPending(@Param("id") UUID id, @Param("status") String status,
                       @Param("decidedBy") String decidedBy, @Param("reason") String reason,
                       @Param("at") java.time.OffsetDateTime at);

    /** CR-185 BIZ-245: 워크플로우 run 의 대기 줄을 한 번에 닫는다. 반환은 닫은 줄 수. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE PendingApprovalEntity a SET a.status = :status, a.approvedBy = :decidedBy, "
            + "a.reason = :reason, a.resolvedAt = :at "
            + "WHERE a.actionLogId = :runId AND a.approvalChannel = 'workflow' AND a.status = 'pending'")
    int closeWorkflowIfPending(@Param("runId") UUID runId, @Param("status") String status,
                               @Param("decidedBy") String decidedBy, @Param("reason") String reason,
                               @Param("at") java.time.OffsetDateTime at);
}
