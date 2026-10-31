package com.platform.api;

import com.platform.domain.TtsJobEntity;
import com.platform.speech.TtsJobException;
import com.platform.speech.TtsJobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** CR-166: 음성 생성 작업 조회 응답과 오류 코드. TC-TTS-116(T3-5). */
class TtsJobControllerTest {

    private TtsJobService service;
    private TtsJobController controller;

    @BeforeEach
    void setUp() {
        service = mock(TtsJobService.class);
        controller = new TtsJobController(service);
    }

    private static TtsJobEntity job(String status) {
        TtsJobEntity j = new TtsJobEntity();
        j.setJobId(UUID.randomUUID());
        j.setVoice("field-voice-07");
        j.setText("1번.");
        j.setStatus(status);
        j.setCreatedAt(OffsetDateTime.parse("2026-10-31T10:40:00+09:00"));
        return j;
    }

    @Test
    @DisplayName("TC-TTS-116 모양이 틀린 번호는 400 invalid_job_id, 없는 작업은 404 job_not_found")
    void errors() {
        TtsJobException bad = assertThrows(TtsJobException.class, () -> controller.get("not-a-uuid"));
        assertEquals(400, bad.getStatus());
        assertEquals("invalid_job_id", bad.getCode());

        when(service.find(any())).thenReturn(Optional.empty());
        TtsJobException missing = assertThrows(TtsJobException.class, () -> controller.get(UUID.randomUUID().toString()));
        ResponseEntity<ApiResponse<Void>> resp = controller.handle(missing);
        assertEquals(404, resp.getStatusCode().value());
        assertFalse(resp.getBody().success());
        assertEquals("job_not_found", resp.getBody().error());
    }

    @Test
    @DisplayName("TC-TTS-116 소리 칸은 완료일 때만, 까닭은 실패일 때만 싣는다")
    void fieldsByStatus() {
        TtsJobEntity pending = job(TtsJobEntity.PENDING);
        when(service.find(pending.getJobId())).thenReturn(Optional.of(pending));
        Map<String, Object> body = controller.get(pending.getJobId().toString()).getBody().data();
        assertEquals("2026-10-31T01:40:00Z", body.get("created_at"));
        assertEquals("1번.", body.get("text"));
        assertFalse(body.containsKey("audio_base64"));
        assertFalse(body.containsKey("error"));

        TtsJobEntity done = job(TtsJobEntity.COMPLETED);
        done.setAudio(new byte[]{1, 2, 3});
        done.setMimeType("audio/mpeg");
        done.setDurationSec(0.8);
        done.setElapsedSec(12.0);
        when(service.find(done.getJobId())).thenReturn(Optional.of(done));
        body = controller.get(done.getJobId().toString()).getBody().data();
        assertEquals("AQID", body.get("audio_base64"));
        assertEquals("audio/mpeg", body.get("mime_type"));
        assertEquals(0.8, body.get("duration_sec"));
        assertFalse(body.containsKey("error"));

        TtsJobEntity failed = job(TtsJobEntity.FAILED);
        failed.setErrorMessage("generation_failed:silent_audio");
        when(service.find(failed.getJobId())).thenReturn(Optional.of(failed));
        body = controller.get(failed.getJobId().toString()).getBody().data();
        assertEquals("generation_failed:silent_audio", body.get("error"));
        assertFalse(body.containsKey("audio_base64"));
    }
}
