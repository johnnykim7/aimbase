package com.platform.speech;

import com.platform.domain.TtsJobEntity;
import com.platform.tenant.TenantContext;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CR-166: 같은 목소리 음성 생성 작업 맡기기와 조회.
 *
 * <p>서버 전체에서 한 번에 한 작업만 생성기에 보내고, 테넌트를 가리지 않고 맡긴 시각 순서대로 만든다(BIZ-133).
 * 생성기가 문장 하나에 15초 남짓 CPU 를 쓰므로 둘을 겹쳐 보내면 둘 다 느려지고 59 의 다른 서비스가 CPU 를 잃는다.
 *
 * <p>차례는 메모리에만 있다. api 가 다시 뜨면 대기나 진행 중으로 남은 작업은 조회나 같은 글 다시 맡기기가 올 때
 * 맡긴 시각 그대로 차례에 다시 넣는다. 테넌트마다 DB 가 따로라 기동 때 전부 훑지 않는다.
 *
 * <p>작업 스레드에는 맡긴 테넌트를 복원하고 끝나면 지운다({@link TenantContext} 는 ThreadLocal, BIZ-003).
 */
@Service
public class TtsJobService {

    private static final Logger log = LoggerFactory.getLogger(TtsJobService.class);

    static final String GENERATION_FAILED = "generation_failed:";

    public record SubmitResult(TtsJobEntity job, boolean reused) {}

    private record Queued(UUID jobId, String tenantId, OffsetDateTime createdAt, long seq) {}

    private final TtsJobStore store;
    private final TtsSidecarClient sidecar;
    private final Clock clock;
    private final int maxTextLength;
    private final int maxPendingPerTenant;
    private final int retentionDays;

    private final PriorityBlockingQueue<Queued> queue = new PriorityBlockingQueue<>(64,
            Comparator.comparing(Queued::createdAt).thenComparingLong(Queued::seq));
    /** 차례에 있거나 만드는 중인 작업. 여기 없는 대기 작업이 api 재기동으로 차례를 잃은 것이다. */
    private final Set<UUID> tracked = ConcurrentHashMap.newKeySet();
    private final AtomicLong seq = new AtomicLong();
    private final Thread worker;
    private volatile boolean stopped;

    @Autowired
    public TtsJobService(TtsJobStore store,
                         TtsSidecarClient sidecar,
                         @Value("${aimbase.tts.max-text-length:200}") int maxTextLength,
                         @Value("${aimbase.tts.max-pending-per-tenant:500}") int maxPendingPerTenant,
                         @Value("${aimbase.tts.retention-days:7}") int retentionDays) {
        this(store, sidecar, Clock.systemUTC(), maxTextLength, maxPendingPerTenant, retentionDays, true);
    }

    TtsJobService(TtsJobStore store, TtsSidecarClient sidecar, Clock clock,
                  int maxTextLength, int maxPendingPerTenant, int retentionDays, boolean startWorker) {
        this.store = store;
        this.sidecar = sidecar;
        this.clock = clock;
        this.maxTextLength = maxTextLength;
        this.maxPendingPerTenant = maxPendingPerTenant;
        this.retentionDays = retentionDays;
        this.worker = new Thread(this::loop, "tts-job");
        this.worker.setDaemon(true);
        if (startWorker) {
            this.worker.start();
        }
    }

    /** 글은 손대지 않고 그대로 생성기에 보낸다(BIZ-135). */
    public SubmitResult submit(String voice, String text, String createdBy) {
        if (voice == null || voice.isBlank()) {
            throw new TtsJobException(400, "voice_required");
        }
        if (text == null || text.isBlank()) {
            throw new TtsJobException(400, "text_required");
        }
        if (text.codePointCount(0, text.length()) > maxTextLength) {
            throw new TtsJobException(400, "text_too_long:max=" + maxTextLength);
        }

        List<String> voices;
        try {
            voices = sidecar.voices();
        } catch (TtsSidecarClient.UnavailableException e) {
            log.warn("[CR-166] 생성기 응답 없음: {}", e.getMessage());
            throw new TtsJobException(503, "tts_generator_unavailable");
        }
        if (!voices.contains(voice)) {
            throw new TtsJobException(404, "voice_not_found:" + voice);
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        int deleted = store.deleteFinishedBefore(now.minusDays(retentionDays));
        if (deleted > 0) {
            log.info("[CR-166] 보관 기간 지난 작업 {}건 삭제 tenant={}", deleted, TenantContext.getTenantId());
        }

        Optional<TtsJobEntity> reusable = store.findReusable(voice, text);
        if (reusable.isPresent()) {
            requeueIfLost(reusable.get());
            return new SubmitResult(reusable.get(), true);
        }

        if (store.countActive() >= maxPendingPerTenant) {
            throw new TtsJobException(429, "tts_queue_full:max=" + maxPendingPerTenant);
        }

        TtsJobEntity job = new TtsJobEntity();
        job.setJobId(UUID.randomUUID());
        job.setVoice(voice);
        job.setText(text);
        job.setStatus(TtsJobEntity.PENDING);
        job.setCreatedBy(createdBy);
        job.setCreatedAt(now);
        TtsJobEntity saved = store.save(job);
        enqueue(saved);
        log.info("[CR-166] 음성 작업 맡김 job_id={} voice={} {}자 tenant={} 차례 {}건",
                saved.getJobId(), voice, text.codePointCount(0, text.length()), TenantContext.getTenantId(),
                queue.size());
        return new SubmitResult(saved, false);
    }

    public Optional<TtsJobEntity> find(UUID jobId) {
        Optional<TtsJobEntity> job = store.find(jobId);
        job.ifPresent(this::requeueIfLost);
        return job;
    }

    private void requeueIfLost(TtsJobEntity job) {
        if (!job.isActive() || tracked.contains(job.getJobId())) {
            return;
        }
        if (TtsJobEntity.RUNNING.equals(job.getStatus())) {
            store.markPending(job.getJobId());
        }
        if (enqueue(job)) {
            log.info("[CR-166] 차례를 잃은 작업 다시 넣음 job_id={} created_at={}", job.getJobId(), job.getCreatedAt());
        }
    }

    private boolean enqueue(TtsJobEntity job) {
        if (!tracked.add(job.getJobId())) {
            return false;
        }
        queue.add(new Queued(job.getJobId(), TenantContext.getTenantId(), job.getCreatedAt(), seq.incrementAndGet()));
        return true;
    }

    private void loop() {
        while (!stopped) {
            try {
                Queued next = queue.poll(1, TimeUnit.SECONDS);
                if (next != null) {
                    process(next);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 차례의 맨 앞 작업 하나를 만든다. 차례가 비었으면 false. */
    boolean runNext() {
        Queued next = queue.poll();
        if (next == null) {
            return false;
        }
        process(next);
        return true;
    }

    /** 예외를 밖으로 던지지 않고 작업 상태에 남긴다. 작업 스레드가 죽으면 뒤의 작업이 전부 멈춘다. */
    private void process(Queued item) {
        if (item.tenantId() != null) {
            TenantContext.setTenantId(item.tenantId());
        }
        try {
            Optional<TtsJobEntity> running = store.markRunning(item.jobId(), OffsetDateTime.now(clock));
            if (running.isEmpty()) {
                return;
            }
            TtsJobEntity job = running.get();
            try {
                TtsSidecarClient.Synthesis s = sidecar.synthesize(job.getVoice(), job.getText());
                store.markCompleted(item.jobId(), s, OffsetDateTime.now(clock));
                log.info("[CR-166] 음성 작업 완료 job_id={} 소리 {}초, 생성 {}초, {}번째 시도",
                        item.jobId(), s.durationSec(), s.elapsedSec(), s.attempt());
            } catch (TtsSidecarClient.UnavailableException e) {
                if (stopped) {
                    // api 를 내리는 중이라 끊긴 것이다. 진행 중으로 두면 다시 뜬 뒤 조회 때 차례에 다시 들어간다.
                    log.info("[CR-166] api 종료로 생성 중단 job_id={}", item.jobId());
                    return;
                }
                fail(item.jobId(), "tts_generator_unavailable", e.getMessage());
            } catch (TtsSidecarClient.TimeoutException e) {
                fail(item.jobId(), "tts_generator_timeout", e.getMessage());
            } catch (TtsSidecarClient.FailedException e) {
                String code = e.getCode().startsWith(GENERATION_FAILED) ? e.getCode() : GENERATION_FAILED + e.getCode();
                fail(item.jobId(), code, e.getMessage());
            }
        } catch (Exception e) {
            log.error("[CR-166] 음성 작업 처리 실패 job_id={}", item.jobId(), e);
            try {
                store.markFailed(item.jobId(), GENERATION_FAILED + "internal_error", OffsetDateTime.now(clock));
            } catch (Exception inner) {
                log.error("[CR-166] 실패 상태 기록마저 실패 job_id={}", item.jobId(), inner);
            }
        } finally {
            tracked.remove(item.jobId());
            TenantContext.clear();
        }
    }

    private void fail(UUID jobId, String code, String detail) {
        log.warn("[CR-166] 음성 작업 실패 job_id={} {} ({})", jobId, code, detail);
        store.markFailed(jobId, code, OffsetDateTime.now(clock));
    }

    int queued() {
        return queue.size();
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        stopped = true;
        worker.interrupt();
        worker.join(Duration.ofSeconds(5).toMillis());
    }
}
