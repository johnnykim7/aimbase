package com.platform.speech;

import com.platform.domain.TranscribeJobEntity;
import com.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CR-133: 회의녹음 배치 전사 job 오케스트레이션.
 *
 * <p>job 을 PENDING 으로 적재하고 즉시 반환한 뒤, 별도 스레드에서 사이드카를 호출한다.
 * 1시간 회의가 ~4분 걸리므로 동기 응답은 불가능하다.
 *
 * <p><b>테넌트 전파 주의</b>: {@link TenantContext} 는 ThreadLocal 이라 워커 스레드로 자동 전파되지
 * 않는다. 전파를 빠뜨리면 다른 테넌트 DB 에 쓰거나 DataSource 를 못 찾는다(BIZ-003).
 * 그래서 제출 시점의 tenantId 를 캡처해 워커 첫 줄에서 복원하고 finally 에서 지운다.
 *
 * <p>상태 전이는 {@link TranscribeJobStore} 에 위임한다 — 같은 빈 내부 호출은
 * {@code @Transactional} 프록시를 타지 않기 때문이다.
 */
@Service
public class TranscribeJobService {

    private static final Logger log = LoggerFactory.getLogger(TranscribeJobService.class);

    private final TranscribeJobStore store;
    private final LocalWhisperSpeechService sidecar;
    private final ExecutorService executor;

    public TranscribeJobService(TranscribeJobStore store,
                                LocalWhisperSpeechService sidecar) {
        this.store = store;
        this.sidecar = sidecar;
        // 전사는 사이드카가 직렬 처리하므로 BE 가 무한정 밀어넣어도 이득이 없다.
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "transcribe-job");
            t.setDaemon(true);
            return t;
        });
    }

    /** job 을 등록하고 즉시 job_id 를 반환한다. 실제 전사는 백그라운드에서 진행된다. */
    public TranscribeJobEntity submit(byte[] audio, String mimeType, String filename,
                                      String language, String connectionId, String createdBy) {
        TranscribeJobEntity job = new TranscribeJobEntity();
        job.setJobId(UUID.randomUUID());
        job.setConnectionId(connectionId);
        job.setFilename(filename);
        job.setMimeType(mimeType);
        job.setSizeBytes((long) audio.length);
        job.setLanguage(language);
        job.setStatus(TranscribeJobEntity.PENDING);
        job.setCreatedBy(createdBy);
        TranscribeJobEntity saved = store.save(job);

        String tenantId = TenantContext.getTenantId();
        UUID jobId = saved.getJobId();
        executor.submit(() -> runJob(jobId, tenantId, audio, mimeType, filename, language, connectionId));

        log.info("[CR-133] 전사 job 등록: job_id={} file={} ({} bytes) tenant={}",
                jobId, filename, audio.length, tenantId);
        return saved;
    }

    /** 워커 본체. 예외를 밖으로 던지지 않고 job 상태에 남긴다. */
    private void runJob(UUID jobId, String tenantId, byte[] audio, String mimeType,
                        String filename, String language, String connectionId) {
        // ThreadLocal 은 상속되지 않으므로 워커에서 직접 복원해야 한다.
        if (tenantId != null) {
            TenantContext.setTenantId(tenantId);
        }
        try {
            store.markRunning(jobId);
            LocalWhisperSpeechService.BatchTranscribeResult r =
                    sidecar.transcribe(audio, mimeType, filename, language, connectionId);
            store.markCompleted(jobId, r);
            log.info("[CR-133] 전사 완료: job_id={} {}초 오디오 / {}초 소요",
                    jobId, r.durationSec(), r.elapsedSec());
        } catch (Exception e) {
            log.warn("[CR-133] 전사 실패: job_id={} — {}", jobId, e.getMessage(), e);
            try {
                store.markFailed(jobId, e.getMessage());
            } catch (Exception inner) {
                log.error("[CR-133] 실패 상태 기록마저 실패: job_id={}", jobId, inner);
            }
        } finally {
            TenantContext.clear();
        }
    }

    public Optional<TranscribeJobEntity> find(UUID jobId) {
        return store.find(jobId);
    }
}
