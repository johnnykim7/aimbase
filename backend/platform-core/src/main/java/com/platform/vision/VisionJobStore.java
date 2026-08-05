package com.platform.vision;

import com.platform.domain.VisionJobEntity;
import com.platform.repository.VisionJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * CR-137: 영상 판독 job 상태 전이 전용 저장 빈.
 *
 * <p>{@link VisionJobService} 와 분리한 이유: {@code @Transactional} 은 Spring 프록시로 동작하므로
 * <b>같은 빈 내부 호출은 트랜잭션이 걸리지 않는다</b>(self-invocation).
 * 워커 스레드에서 상태를 전이하려면 별도 빈을 거쳐야 실제로 커밋된다.
 * CR-133 {@code TranscribeJobStore} 와 같은 구조.
 */
@Service
public class VisionJobStore {

    private final VisionJobRepository repository;

    public VisionJobStore(VisionJobRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public VisionJobEntity save(VisionJobEntity job) {
        return repository.save(job);
    }

    @Transactional
    public void markRunning(UUID jobId) {
        repository.findByJobId(jobId).ifPresent(j -> {
            j.setStatus(VisionJobEntity.RUNNING);
            j.setStartedAt(OffsetDateTime.now());
            repository.save(j);
        });
    }

    /** 프레임 추출 직후 메타데이터를 기록한다(판독 전이라도 진행 상황이 보이게). */
    @Transactional
    public void markFramesExtracted(UUID jobId, int frameCount, double durationSec, String framesPath) {
        repository.findByJobId(jobId).ifPresent(j -> {
            j.setFrameCount(frameCount);
            if (durationSec > 0) j.setDurationSec(durationSec);
            j.setFramesPath(framesPath);
            repository.save(j);
        });
    }

    @Transactional
    public void markCompleted(UUID jobId, String result, Map<String, Object> resultJson, double elapsedSec) {
        repository.findByJobId(jobId).ifPresent(j -> {
            j.setStatus(VisionJobEntity.COMPLETED);
            j.setResult(result);
            j.setResultJson(resultJson);
            j.setElapsedSec(elapsedSec);
            j.setFinishedAt(OffsetDateTime.now());
            repository.save(j);
        });
    }

    @Transactional
    public void markFailed(UUID jobId, String message) {
        repository.findByJobId(jobId).ifPresent(j -> {
            j.setStatus(VisionJobEntity.FAILED);
            j.setErrorMessage(message);
            j.setFinishedAt(OffsetDateTime.now());
            repository.save(j);
        });
    }

    @Transactional(readOnly = true)
    public Optional<VisionJobEntity> find(UUID jobId) {
        return repository.findByJobId(jobId);
    }
}
