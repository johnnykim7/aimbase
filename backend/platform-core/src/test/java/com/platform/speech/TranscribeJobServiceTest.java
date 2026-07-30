package com.platform.speech;

import com.platform.domain.TranscribeJobEntity;
import com.platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CR-133: 전사 job 오케스트레이션 테스트.
 *
 * 핵심 검증 2가지:
 *  - 워커 스레드로 TenantContext 가 전파되는가 (누락 시 타 테넌트 DB 오염, BIZ-003)
 *  - 사이드카 실패가 job FAILED 로 기록되는가 (예외를 삼키고 PENDING 에 방치하면 안 됨)
 */
class TranscribeJobServiceTest {

    private TranscribeJobStore store;
    private LocalWhisperSpeechService sidecar;
    private TranscribeJobService service;

    @BeforeEach
    void setUp() {
        store = mock(TranscribeJobStore.class);
        sidecar = mock(LocalWhisperSpeechService.class);
        // save 는 전달받은 엔티티를 그대로 돌려준다.
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new TranscribeJobService(store, sidecar);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("submit 은 PENDING job 을 즉시 반환한다(전사를 기다리지 않는다)")
    void submit_returnsImmediately() {
        TenantContext.setTenantId("bp_wes");
        // 사이드카가 느려도 submit 은 즉시 반환해야 한다.
        when(sidecar.transcribe(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            Thread.sleep(3000);
            return new LocalWhisperSpeechService.BatchTranscribeResult("t", "ko", 1.0, 1.0, List.of());
        });

        long started = System.currentTimeMillis();
        TranscribeJobEntity job = service.submit("audio".getBytes(StandardCharsets.UTF_8),
                "audio/wav", "m.wav", "ko", null, "tester");
        long elapsed = System.currentTimeMillis() - started;

        assertEquals(TranscribeJobEntity.PENDING, job.getStatus());
        assertNotNull(job.getJobId());
        assertEquals(5L, job.getSizeBytes());
        assertTrue(elapsed < 1000, "submit 이 전사를 기다렸다: " + elapsed + "ms");
    }

    @Test
    @DisplayName("워커 스레드에 TenantContext 가 전파된다")
    void worker_propagatesTenantContext() throws Exception {
        TenantContext.setTenantId("bp_wes");
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> seenTenant = new AtomicReference<>();

        when(sidecar.transcribe(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            seenTenant.set(TenantContext.getTenantId());
            latch.countDown();
            return new LocalWhisperSpeechService.BatchTranscribeResult(
                    "결과", "ko", 10.0, 2.0, List.of(Map.of("start", 0.0, "end", 1.0, "text", "결과")));
        });

        service.submit("a".getBytes(StandardCharsets.UTF_8), "audio/wav", "m.wav", "ko", null, "tester");

        assertTrue(latch.await(5, TimeUnit.SECONDS), "워커가 실행되지 않았다");
        assertEquals("bp_wes", seenTenant.get(),
                "TenantContext 가 워커로 전파되지 않으면 타 테넌트 DB 를 건드린다");
    }

    @Test
    @DisplayName("전사 성공 시 RUNNING → COMPLETED 로 전이한다")
    void worker_marksCompleted() throws Exception {
        TenantContext.setTenantId("bp_wes");
        var result = new LocalWhisperSpeechService.BatchTranscribeResult(
                "회의 전문", "ko", 632.9, 43.7, List.of(Map.of("start", 0.0, "end", 5.0, "text", "회의")));
        when(sidecar.transcribe(any(), any(), any(), any(), any())).thenReturn(result);

        TranscribeJobEntity job = service.submit("a".getBytes(StandardCharsets.UTF_8),
                "audio/wav", "m.wav", "ko", null, "tester");
        UUID jobId = job.getJobId();

        verify(store, timeout(5000)).markRunning(eq(jobId));
        verify(store, timeout(5000)).markCompleted(eq(jobId), eq(result));
        verify(store, never()).markFailed(any(), any());
    }

    @Test
    @DisplayName("사이드카 실패는 FAILED 로 기록된다(예외를 삼키지 않는다)")
    void worker_marksFailed() throws Exception {
        TenantContext.setTenantId("bp_wes");
        when(sidecar.transcribe(any(), any(), any(), any(), any()))
                .thenThrow(SpeechException.timeout(new RuntimeException("boom")));

        TranscribeJobEntity job = service.submit("a".getBytes(StandardCharsets.UTF_8),
                "audio/wav", "m.wav", "ko", null, "tester");

        verify(store, timeout(5000)).markFailed(eq(job.getJobId()), contains("timeout"));
        verify(store, never()).markCompleted(any(), any());
    }

    @Test
    @DisplayName("테넌트가 없어도(플랫폼 경로) 워커가 죽지 않는다")
    void worker_survivesNullTenant() throws Exception {
        TenantContext.clear();
        when(sidecar.transcribe(any(), any(), any(), any(), any())).thenReturn(
                new LocalWhisperSpeechService.BatchTranscribeResult("t", "ko", 1.0, 1.0, List.of()));

        TranscribeJobEntity job = service.submit("a".getBytes(StandardCharsets.UTF_8),
                "audio/wav", "m.wav", "ko", null, "tester");

        verify(store, timeout(5000)).markCompleted(eq(job.getJobId()), any());
    }
}
