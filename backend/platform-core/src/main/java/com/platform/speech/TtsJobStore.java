package com.platform.speech;

import com.platform.domain.TtsJobEntity;
import com.platform.repository.TtsJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * CR-166: 음성 생성 작업 상태 전이 전용 저장 빈.
 *
 * <p>{@link TtsJobService} 의 작업 스레드가 같은 빈 안에서 부르면 {@code @Transactional} 프록시를
 * 타지 않으므로 상태 전이를 별도 빈에 둔다(CR-133 {@link TranscribeJobStore} 와 같은 까닭).
 */
@Service
public class TtsJobStore {

    private final TtsJobRepository repository;

    public TtsJobStore(TtsJobRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public TtsJobEntity save(TtsJobEntity job) {
        return repository.save(job);
    }

    @Transactional(readOnly = true)
    public Optional<TtsJobEntity> find(UUID jobId) {
        return repository.findByJobId(jobId);
    }

    /** 같은 음색, 같은 글, 같은 차례의 가장 최근 작업. 실패한 작업은 다시 맡길 수 있어야 하므로 고르지 않는다(BIZ-134). */
    @Transactional(readOnly = true)
    public Optional<TtsJobEntity> findReusable(String voice, String text, int take) {
        return repository.findFirstByVoiceAndTextAndTakeAndStatusNotOrderByCreatedAtDesc(
                voice, text, take, TtsJobEntity.FAILED);
    }

    @Transactional(readOnly = true)
    public long countActive() {
        return repository.countByStatusIn(TtsJobEntity.ACTIVE);
    }

    @Transactional
    public int deleteFinishedBefore(OffsetDateTime cutoff) {
        return repository.deleteFinishedBefore(TtsJobEntity.FINISHED, cutoff);
    }

    /** 이미 끝났거나 지워진 작업이면 비어 있다. 작업 스레드는 그때 만들지 않고 넘어간다. */
    @Transactional
    public Optional<TtsJobEntity> markRunning(UUID jobId, OffsetDateTime now) {
        return repository.findByJobId(jobId).filter(TtsJobEntity::isActive).map(j -> {
            j.setStatus(TtsJobEntity.RUNNING);
            j.setStartedAt(now);
            return repository.save(j);
        });
    }

    @Transactional
    public void markPending(UUID jobId) {
        repository.findByJobId(jobId).filter(j -> TtsJobEntity.RUNNING.equals(j.getStatus())).ifPresent(j -> {
            j.setStatus(TtsJobEntity.PENDING);
            j.setStartedAt(null);
            repository.save(j);
        });
    }

    @Transactional
    public void markCompleted(UUID jobId, TtsSidecarClient.Synthesis s, OffsetDateTime now) {
        repository.findByJobId(jobId).ifPresent(j -> {
            j.setStatus(TtsJobEntity.COMPLETED);
            j.setAudio(s.audio());
            j.setMimeType(s.mimeType());
            j.setDurationSec(s.durationSec());
            j.setElapsedSec(s.elapsedSec());
            j.setFinishedAt(now);
            repository.save(j);
        });
    }

    @Transactional
    public void markFailed(UUID jobId, String error, OffsetDateTime now) {
        repository.findByJobId(jobId).ifPresent(j -> {
            j.setStatus(TtsJobEntity.FAILED);
            j.setErrorMessage(error);
            j.setFinishedAt(now);
            repository.save(j);
        });
    }
}
