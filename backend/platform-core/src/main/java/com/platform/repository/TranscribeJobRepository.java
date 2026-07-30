package com.platform.repository;

import com.platform.domain.TranscribeJobEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** CR-133: 회의녹음 전사 job 저장소 (Tenant DB). */
@Repository
public interface TranscribeJobRepository extends JpaRepository<TranscribeJobEntity, Long> {

    Optional<TranscribeJobEntity> findByJobId(UUID jobId);

    Page<TranscribeJobEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
