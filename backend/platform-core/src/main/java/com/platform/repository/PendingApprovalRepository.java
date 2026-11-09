package com.platform.repository;

import com.platform.domain.PendingApprovalEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PendingApprovalRepository extends JpaRepository<PendingApprovalEntity, UUID> {
    Page<PendingApprovalEntity> findByStatusOrderByRequestedAtDesc(String status, Pageable pageable);
    List<PendingApprovalEntity> findByActionLogId(UUID actionLogId);

    /** CR-174 BIZ-144: 승인 시한이 지난 대기. 감시기가 timeout 으로 닫는다. */
    List<PendingApprovalEntity> findByStatusAndApprovalChannelAndTimeoutAtBefore(
            String status, String approvalChannel, java.time.OffsetDateTime before);
}
