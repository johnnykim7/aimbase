package com.platform.speech;

import com.platform.domain.TranscribeJobEntity;
import com.platform.repository.TranscribeJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * CR-133: 전사 job 상태 전이 전용 저장 빈.
 *
 * <p>{@link TranscribeJobService} 와 분리한 이유: {@code @Transactional} 은 Spring 프록시로
 * 동작하므로 <b>같은 빈 내부 호출은 트랜잭션이 걸리지 않는다</b>(self-invocation).
 * 워커 스레드에서 상태를 전이하려면 별도 빈을 거쳐야 실제로 커밋된다.
 */
@Service
public class TranscribeJobStore {

    private final TranscribeJobRepository repository;

    public TranscribeJobStore(TranscribeJobRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public TranscribeJobEntity save(TranscribeJobEntity job) {
        return repository.save(job);
    }

    @Transactional
    public void markRunning(UUID jobId) {
        repository.findByJobId(jobId).ifPresent(j -> {
            j.setStatus(TranscribeJobEntity.RUNNING);
            j.setStartedAt(OffsetDateTime.now());
            repository.save(j);
        });
    }

    @Transactional
    public void markCompleted(UUID jobId, LocalWhisperSpeechService.BatchTranscribeResult r) {
        repository.findByJobId(jobId).ifPresent(j -> {
            j.setStatus(TranscribeJobEntity.COMPLETED);
            j.setText(r.text());
            j.setSegments(r.segments());
            j.setDetectedLang(r.language());
            j.setDurationSec(r.durationSec());
            j.setElapsedSec(r.elapsedSec());
            j.setFinishedAt(OffsetDateTime.now());
            repository.save(j);
        });
    }

    @Transactional
    public void markFailed(UUID jobId, String message) {
        repository.findByJobId(jobId).ifPresent(j -> {
            j.setStatus(TranscribeJobEntity.FAILED);
            j.setErrorMessage(message);
            j.setFinishedAt(OffsetDateTime.now());
            repository.save(j);
        });
    }

    @Transactional(readOnly = true)
    public Optional<TranscribeJobEntity> find(UUID jobId) {
        return repository.findByJobId(jobId);
    }
}
