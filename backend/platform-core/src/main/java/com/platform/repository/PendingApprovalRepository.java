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
}
