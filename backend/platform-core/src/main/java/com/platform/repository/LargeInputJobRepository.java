package com.platform.repository;

import com.platform.domain.LargeInputJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * CR-120: 대용량 입력 전수 분석 job 상태 저장소 (tenant DB).
 */
@Repository
public interface LargeInputJobRepository extends JpaRepository<LargeInputJobEntity, Long> {

    Optional<LargeInputJobEntity> findByJobId(UUID jobId);

    List<LargeInputJobEntity> findByRunIdOrderByCreatedAtAsc(UUID runId);
}
