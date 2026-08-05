package com.platform.repository;

import com.platform.domain.VisionJobEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** CR-137: 영상 판독 job 저장소 (Tenant DB). */
@Repository
public interface VisionJobRepository extends JpaRepository<VisionJobEntity, Long> {

    Optional<VisionJobEntity> findByJobId(UUID jobId);

    Page<VisionJobEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** GC 대상 — 완료/실패 후 TTL 이 지난 job (원본 영상·프레임 삭제용, BIZ-114). */
    List<VisionJobEntity> findTop100ByFinishedAtBefore(OffsetDateTime threshold);
}
