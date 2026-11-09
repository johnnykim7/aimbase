package com.platform.repository;

import com.platform.domain.TtsJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/** CR-166: 같은 목소리 음성 생성 작업 저장소 (Tenant DB). */
@Repository
public interface TtsJobRepository extends JpaRepository<TtsJobEntity, Long> {

    Optional<TtsJobEntity> findByJobId(UUID jobId);

    Optional<TtsJobEntity> findFirstByVoiceAndTextAndTakeAndStatusNotOrderByCreatedAtDesc(
            String voice, String text, int take, String status);

    long countByStatusIn(Collection<String> statuses);

    @Modifying
    @Query("DELETE FROM TtsJobEntity j WHERE j.status IN :statuses AND j.finishedAt < :cutoff")
    int deleteFinishedBefore(@Param("statuses") Collection<String> statuses,
                             @Param("cutoff") OffsetDateTime cutoff);
}
