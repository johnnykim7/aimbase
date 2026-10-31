package com.platform.speech;

import com.platform.domain.TtsJobEntity;
import com.platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CR-166: 음성 생성 작업 맡기기, 차례, 상태 테스트. TC-TTS-101 ~ 115(T3-5).
 *
 * <p>저장은 테넌트마다 따로 둔 메모리 저장소로 바꿔 끼워, 작업 스레드가 맡긴 테넌트의 DB 에 쓰는지 본다(BIZ-003).
 */
class TtsJobServiceTest {

    private static final String VOICE = "field-voice-07";

    private TenantStore store;
    private TtsSidecarClient sidecar;
    private MutableClock clock;
    private TtsJobService service;

    @BeforeEach
    void setUp() {
        store = new TenantStore();
        sidecar = mock(TtsSidecarClient.class);
        when(sidecar.voices()).thenReturn(List.of(VOICE));
        clock = new MutableClock(Instant.parse("2026-10-31T01:00:00Z"));
        service = newService(500);
        TenantContext.setTenantId("tenant_a");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private TtsJobService newService(int maxPending) {
        return new TtsJobService(store, sidecar, clock, 200, maxPending, 7, false);
    }

    private static TtsSidecarClient.Synthesis mp3() {
        return new TtsSidecarClient.Synthesis(new byte[]{1, 2, 3}, "audio/mpeg", 1.2, 15.5, 1);
    }

    private static int status(Runnable r) {
        TtsJobException e = assertThrows(TtsJobException.class, r::run);
        return e.getStatus();
    }

    private static String code(Runnable r) {
        return assertThrows(TtsJobException.class, r::run).getCode();
    }

    @Test
    @DisplayName("TC-TTS-101 맡기면 대기 작업이 생기고 차례에 들어간다")
    void submit() {
        TtsJobService.SubmitResult r = service.submit(VOICE, "검수대 3번으로 이동하세요.", "bp-wms");

        assertFalse(r.reused());
        assertEquals(TtsJobEntity.PENDING, r.job().getStatus());
        assertEquals(OffsetDateTime.parse("2026-10-31T01:00:00Z"), r.job().getCreatedAt());
        assertEquals(1, store.rows("tenant_a").size());
        assertEquals(1, service.queued());
        verify(sidecar, never()).synthesize(any(), any());
    }

    @Test
    @DisplayName("TC-TTS-102 빈 글, 공백만 있는 글, 음색 없음은 400 이고 행을 만들지 않는다")
    void blankText() {
        assertEquals("text_required", code(() -> service.submit(VOICE, null, "u")));
        assertEquals("text_required", code(() -> service.submit(VOICE, " \n\t ", "u")));
        assertEquals("voice_required", code(() -> service.submit(" ", "1번.", "u")));
        assertEquals(400, status(() -> service.submit(VOICE, "", "u")));
        assertTrue(store.rows("tenant_a").isEmpty());
    }

    @Test
    @DisplayName("TC-TTS-103 글 길이는 글자(코드 포인트)로 200자까지")
    void textLength() {
        service.submit(VOICE, "가".repeat(200), "u");
        // 이모지는 UTF-16 으로 두 칸이지만 한 글자다.
        service.submit(VOICE, "😀".repeat(200), "u");

        TtsJobException e = assertThrows(TtsJobException.class, () -> service.submit(VOICE, "가".repeat(201), "u"));
        assertEquals(400, e.getStatus());
        assertEquals("text_too_long:max=200", e.getCode());
        assertEquals(2, store.rows("tenant_a").size());
    }

    @Test
    @DisplayName("TC-TTS-104 글을 다듬지 않고 그대로 생성기에 보낸다")
    void textUnchanged() {
        String text = "  \"3번\" 칸,\n세 개.  ";
        when(sidecar.synthesize(eq(VOICE), anyString())).thenReturn(mp3());

        service.submit(VOICE, text, "u");
        service.runNext();

        verify(sidecar).synthesize(VOICE, text);
        assertEquals(text, store.rows("tenant_a").get(0).getText());
    }

    @Test
    @DisplayName("TC-TTS-105 생성기에 없는 음색은 404 이고 행을 만들지 않는다")
    void unknownVoice() {
        TtsJobException e = assertThrows(TtsJobException.class, () -> service.submit("field-voice-99", "1번.", "u"));
        assertEquals(404, e.getStatus());
        assertEquals("voice_not_found:field-voice-99", e.getCode());
        assertTrue(store.rows("tenant_a").isEmpty());
    }

    @Test
    @DisplayName("TC-TTS-106 생성기가 꺼져 있으면 503 이고 행을 만들지 않는다")
    void generatorDown() {
        when(sidecar.voices()).thenThrow(new TtsSidecarClient.UnavailableException("refused", null));

        TtsJobException e = assertThrows(TtsJobException.class, () -> service.submit(VOICE, "1번.", "u"));
        assertEquals(503, e.getStatus());
        assertEquals("tts_generator_unavailable", e.getCode());
        assertTrue(store.rows("tenant_a").isEmpty());
    }

    @Test
    @DisplayName("TC-TTS-107 같은 음색 같은 글은 대기, 진행, 완료 어느 때나 같은 작업을 돌려준다")
    void reuse() {
        when(sidecar.synthesize(any(), any())).thenReturn(mp3());
        UUID first = service.submit(VOICE, "1번.", "u").job().getJobId();

        TtsJobService.SubmitResult pending = service.submit(VOICE, "1번.", "u");
        assertTrue(pending.reused());
        assertEquals(first, pending.job().getJobId());
        assertEquals(1, service.queued());

        service.runNext();
        // 시험 스레드에서 차례를 돌렸으므로 작업이 비운 테넌트를 되돌린다.
        TenantContext.setTenantId("tenant_a");
        TtsJobService.SubmitResult done = service.submit(VOICE, "1번.", "u");
        assertTrue(done.reused());
        assertEquals(TtsJobEntity.COMPLETED, done.job().getStatus());
        assertEquals(1, store.rows("tenant_a").size());
        assertEquals(0, service.queued());
    }

    @Test
    @DisplayName("TC-TTS-108 같은 글이 실패로만 남아 있으면 새 작업을 만든다")
    void failedIsNotReused() {
        when(sidecar.synthesize(any(), any())).thenThrow(new TtsSidecarClient.FailedException("generation_failed:silent_audio"));
        UUID first = service.submit(VOICE, "1번.", "u").job().getJobId();
        service.runNext();
        TenantContext.setTenantId("tenant_a");

        TtsJobService.SubmitResult again = service.submit(VOICE, "1번.", "u");
        assertFalse(again.reused());
        assertNotEquals(first, again.job().getJobId());
        assertEquals(2, store.rows("tenant_a").size());
    }

    @Test
    @DisplayName("TC-TTS-109 테넌트마다 대기와 진행 중이 상한이면 429, 다른 테넌트는 맡겨진다")
    void queueFull() {
        service = newService(2);
        service.submit(VOICE, "1번.", "u");
        service.submit(VOICE, "2번.", "u");

        TtsJobException e = assertThrows(TtsJobException.class, () -> service.submit(VOICE, "3번.", "u"));
        assertEquals(429, e.getStatus());
        assertEquals("tts_queue_full:max=2", e.getCode());
        // 같은 글 다시 맡기기는 행이 늘지 않으므로 상한에 걸리지 않는다.
        assertTrue(service.submit(VOICE, "1번.", "u").reused());

        TenantContext.setTenantId("tenant_b");
        assertFalse(service.submit(VOICE, "3번.", "u").reused());
    }

    @Test
    @DisplayName("TC-TTS-110 테넌트를 가리지 않고 맡긴 시각 순서대로 만든다")
    void fifoAcrossTenants() {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        when(sidecar.synthesize(any(), any())).thenAnswer(inv -> {
            order.add(TenantContext.getTenantId() + ":" + inv.getArgument(1));
            return mp3();
        });

        service.submit(VOICE, "a1", "u");
        clock.plus(Duration.ofSeconds(1));
        TenantContext.setTenantId("tenant_b");
        service.submit(VOICE, "b1", "u");
        clock.plus(Duration.ofSeconds(1));
        TenantContext.setTenantId("tenant_a");
        service.submit(VOICE, "a2", "u");

        while (service.runNext()) {
            // 차례가 빌 때까지
        }
        assertEquals(List.of("tenant_a:a1", "tenant_b:b1", "tenant_a:a2"), order);
    }

    @Test
    @DisplayName("TC-TTS-110 작업 스레드는 생성기를 한 번에 하나만 부른다")
    void oneAtATime() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        AtomicInteger done = new AtomicInteger();
        when(sidecar.synthesize(any(), any())).thenAnswer(inv -> {
            maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            Thread.sleep(100);
            active.decrementAndGet();
            done.incrementAndGet();
            return mp3();
        });
        TtsJobService running = new TtsJobService(store, sidecar, clock, 200, 500, 7, true);
        try {
            for (int i = 0; i < 4; i++) {
                running.submit(VOICE, "문장 " + i, "u");
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (done.get() < 4 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
        } finally {
            running.shutdown();
        }
        assertEquals(4, done.get());
        assertEquals(1, maxActive.get());
    }

    @Test
    @DisplayName("TC-TTS-111 작업 스레드는 맡긴 테넌트의 DB 에 쓰고 끝나면 테넌트를 비운다")
    void tenantPropagation() {
        List<String> seen = new ArrayList<>();
        when(sidecar.synthesize(any(), any())).thenAnswer(inv -> {
            seen.add(TenantContext.getTenantId());
            return mp3();
        });
        service.submit(VOICE, "1번.", "u");

        TenantContext.setTenantId("tenant_b");
        service.runNext();

        assertEquals(List.of("tenant_a"), seen);
        assertEquals(TtsJobEntity.COMPLETED, store.rows("tenant_a").get(0).getStatus());
        assertTrue(store.rows("tenant_b").isEmpty());
        assertNull(TenantContext.getTenantId());
    }

    @Test
    @DisplayName("TC-TTS-112 생성기가 소리를 주면 완료와 소리, 길이, 걸린 시간을 남긴다")
    void completed() {
        when(sidecar.synthesize(any(), any())).thenReturn(mp3());
        UUID id = service.submit(VOICE, "1번.", "u").job().getJobId();
        clock.plus(Duration.ofSeconds(3));
        service.runNext();

        TtsJobEntity job = store.rows("tenant_a").get(0);
        assertEquals(id, job.getJobId());
        assertEquals(TtsJobEntity.COMPLETED, job.getStatus());
        assertArrayEquals(new byte[]{1, 2, 3}, job.getAudio());
        assertEquals("audio/mpeg", job.getMimeType());
        assertEquals(1.2, job.getDurationSec());
        assertEquals(15.5, job.getElapsedSec());
        assertEquals(OffsetDateTime.parse("2026-10-31T01:00:03Z"), job.getStartedAt());
        assertNotNull(job.getFinishedAt());
    }

    @Test
    @DisplayName("TC-TTS-113 생성 실패, 연결 실패, 시간 초과는 실패와 까닭을 남긴다")
    void failures() {
        Map<RuntimeException, String> cases = Map.of(
                new TtsSidecarClient.FailedException("generation_failed:hit_token_limit"), "generation_failed:hit_token_limit",
                new TtsSidecarClient.FailedException("generator_http_502"), "generation_failed:generator_http_502",
                new TtsSidecarClient.UnavailableException("refused", null), "tts_generator_unavailable",
                new TtsSidecarClient.TimeoutException(null), "tts_generator_timeout",
                new IllegalStateException("boom"), "generation_failed:internal_error");
        int i = 0;
        for (Map.Entry<RuntimeException, String> c : cases.entrySet()) {
            String text = "문장 " + (i++);
            when(sidecar.synthesize(VOICE, text)).thenThrow(c.getKey());
            UUID id = service.submit(VOICE, text, "u").job().getJobId();
            service.runNext();
            TenantContext.setTenantId("tenant_a");

            TtsJobEntity job = store.find(id).orElseThrow();
            assertEquals(TtsJobEntity.FAILED, job.getStatus(), c.getValue());
            assertEquals(c.getValue(), job.getErrorMessage());
            assertNotNull(job.getFinishedAt());
        }
    }

    @Test
    @DisplayName("TC-TTS-114 api 가 다시 떠 차례를 잃은 대기, 진행 작업은 조회 때 맡긴 시각 그대로 다시 넣는다")
    void requeueAfterRestart() {
        List<String> order = new ArrayList<>();
        when(sidecar.synthesize(any(), any())).thenAnswer(inv -> {
            order.add(inv.getArgument(1));
            return mp3();
        });
        UUID older = service.submit(VOICE, "먼저", "u").job().getJobId();
        clock.plus(Duration.ofSeconds(1));
        UUID wasRunning = service.submit(VOICE, "진행중", "u").job().getJobId();
        store.find(wasRunning).orElseThrow().setStatus(TtsJobEntity.RUNNING);

        // 다시 뜬 api. 메모리의 차례는 비어 있다.
        TtsJobService restarted = newService(500);
        clock.plus(Duration.ofSeconds(1));
        restarted.submit(VOICE, "새로", "u");
        assertEquals(1, restarted.queued());

        restarted.find(wasRunning);
        restarted.find(wasRunning);
        assertEquals(TtsJobEntity.PENDING, store.find(wasRunning).orElseThrow().getStatus());
        assertNull(store.find(wasRunning).orElseThrow().getStartedAt());
        assertTrue(restarted.submit(VOICE, "먼저", "u").reused());
        assertEquals(3, restarted.queued());

        while (restarted.runNext()) {
            TenantContext.setTenantId("tenant_a");
        }
        assertEquals(List.of("먼저", "진행중", "새로"), order);
        assertEquals(TtsJobEntity.COMPLETED, store.find(older).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("TC-TTS-115 맡길 때 보관 기간이 지난 끝난 작업만 지운다")
    void retention() {
        OffsetDateTime old = OffsetDateTime.parse("2026-10-23T00:00:00Z");
        TtsJobEntity oldDone = store.put("tenant_a", "옛 완료", TtsJobEntity.COMPLETED, old, old);
        TtsJobEntity oldFailed = store.put("tenant_a", "옛 실패", TtsJobEntity.FAILED, old, old);
        TtsJobEntity oldPending = store.put("tenant_a", "옛 대기", TtsJobEntity.PENDING, old, null);
        TtsJobEntity recent = store.put("tenant_a", "어제 완료", TtsJobEntity.COMPLETED,
                OffsetDateTime.parse("2026-10-30T00:00:00Z"), OffsetDateTime.parse("2026-10-30T00:00:10Z"));
        TtsJobEntity otherTenant = store.put("tenant_b", "옛 완료", TtsJobEntity.COMPLETED, old, old);

        service.submit(VOICE, "새 글", "u");

        List<UUID> left = store.rows("tenant_a").stream().map(TtsJobEntity::getJobId).toList();
        assertFalse(left.contains(oldDone.getJobId()));
        assertFalse(left.contains(oldFailed.getJobId()));
        assertTrue(left.contains(oldPending.getJobId()));
        assertTrue(left.contains(recent.getJobId()));
        assertEquals(List.of(otherTenant.getJobId()),
                store.rows("tenant_b").stream().map(TtsJobEntity::getJobId).toList());
    }

    /** 테넌트마다 따로 둔 메모리 저장소. 쓰는 곳은 그 순간의 {@link TenantContext} 로 정한다. */
    static class TenantStore extends TtsJobStore {

        private final Map<String, List<TtsJobEntity>> byTenant = new ConcurrentHashMap<>();

        TenantStore() {
            super(null);
        }

        List<TtsJobEntity> rows(String tenant) {
            return byTenant.computeIfAbsent(tenant, t -> new CopyOnWriteArrayList<>());
        }

        private List<TtsJobEntity> current() {
            String tenant = TenantContext.getTenantId();
            if (tenant == null) {
                throw new IllegalStateException("tenant not set");
            }
            return rows(tenant);
        }

        TtsJobEntity put(String tenant, String text, String status, OffsetDateTime createdAt, OffsetDateTime finishedAt) {
            TtsJobEntity j = new TtsJobEntity();
            j.setJobId(UUID.randomUUID());
            j.setVoice(VOICE);
            j.setText(text);
            j.setStatus(status);
            j.setCreatedAt(createdAt);
            j.setFinishedAt(finishedAt);
            rows(tenant).add(j);
            return j;
        }

        @Override
        public TtsJobEntity save(TtsJobEntity job) {
            current().add(job);
            return job;
        }

        @Override
        public Optional<TtsJobEntity> find(UUID jobId) {
            return current().stream().filter(j -> j.getJobId().equals(jobId)).findFirst();
        }

        @Override
        public Optional<TtsJobEntity> findReusable(String voice, String text) {
            return current().stream()
                    .filter(j -> j.getVoice().equals(voice) && j.getText().equals(text))
                    .filter(j -> !TtsJobEntity.FAILED.equals(j.getStatus()))
                    .max(Comparator.comparing(TtsJobEntity::getCreatedAt));
        }

        @Override
        public long countActive() {
            return current().stream().filter(TtsJobEntity::isActive).count();
        }

        @Override
        public int deleteFinishedBefore(OffsetDateTime cutoff) {
            List<TtsJobEntity> rows = current();
            int before = rows.size();
            rows.removeIf(j -> TtsJobEntity.FINISHED.contains(j.getStatus())
                    && j.getFinishedAt() != null && j.getFinishedAt().isBefore(cutoff));
            return before - rows.size();
        }

        @Override
        public Optional<TtsJobEntity> markRunning(UUID jobId, OffsetDateTime now) {
            return find(jobId).filter(TtsJobEntity::isActive).map(j -> {
                j.setStatus(TtsJobEntity.RUNNING);
                j.setStartedAt(now);
                return j;
            });
        }

        @Override
        public void markPending(UUID jobId) {
            find(jobId).filter(j -> TtsJobEntity.RUNNING.equals(j.getStatus())).ifPresent(j -> {
                j.setStatus(TtsJobEntity.PENDING);
                j.setStartedAt(null);
            });
        }

        @Override
        public void markCompleted(UUID jobId, TtsSidecarClient.Synthesis s, OffsetDateTime now) {
            find(jobId).ifPresent(j -> {
                j.setStatus(TtsJobEntity.COMPLETED);
                j.setAudio(s.audio());
                j.setMimeType(s.mimeType());
                j.setDurationSec(s.durationSec());
                j.setElapsedSec(s.elapsedSec());
                j.setFinishedAt(now);
            });
        }

        @Override
        public void markFailed(UUID jobId, String error, OffsetDateTime now) {
            find(jobId).ifPresent(j -> {
                j.setStatus(TtsJobEntity.FAILED);
                j.setErrorMessage(error);
                j.setFinishedAt(now);
            });
        }
    }

    static class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void plus(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
