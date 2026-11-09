package com.platform.api;

import com.platform.domain.TtsJobEntity;
import com.platform.speech.TtsJobException;
import com.platform.speech.TtsJobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * CR-166: 같은 목소리 음성 생성 작업 맡기기와 조회(T3-2, 계약 docs/협업/계약_CR-166_같은_목소리_음성_생성_창구.md).
 *
 * <pre>
 *   POST /api/v1/speech/tts-jobs            {voice, text, take} 를 맡기고 job_id 를 바로 받는다
 *   GET  /api/v1/speech/tts-jobs/{job_id}   상태와 끝난 소리
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/speech/tts-jobs")
@Tag(name = "TTS Jobs", description = "CR-166 같은 목소리 음성 생성")
public class TtsJobController {

    /** take 는 같은 글 다시 만들기 차례이고 빠지면 0 이다(CR-176). */
    public record SubmitRequest(String voice, String text, Integer take) {}

    private final TtsJobService jobService;

    public TtsJobController(TtsJobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping
    @Operation(summary = "음성 생성 작업 맡기기 (비동기)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submit(@RequestBody(required = false) SubmitRequest req) {
        TtsJobService.SubmitResult result = req == null
                ? jobService.submit(null, null, null, currentUser())
                : jobService.submit(req.voice(), req.text(), req.take(), currentUser());
        TtsJobEntity job = result.job();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("job_id", job.getJobId().toString());
        body.put("status", job.getStatus());
        body.put("voice", job.getVoice());
        body.put("take", job.getTake());
        body.put("reused", result.reused());
        body.put("created_at", format(job.getCreatedAt()));
        return ResponseEntity.accepted().body(ApiResponse.ok(body));
    }

    @GetMapping("/{jobId}")
    @Operation(summary = "음성 생성 작업 조회")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(@PathVariable("jobId") String jobId) {
        UUID uuid;
        try {
            uuid = UUID.fromString(jobId);
        } catch (IllegalArgumentException e) {
            throw new TtsJobException(400, "invalid_job_id");
        }
        TtsJobEntity job = jobService.find(uuid).orElseThrow(() -> new TtsJobException(404, "job_not_found"));
        return ResponseEntity.ok(ApiResponse.ok(toBody(job)));
    }

    @ExceptionHandler(TtsJobException.class)
    public ResponseEntity<ApiResponse<Void>> handle(TtsJobException e) {
        return ResponseEntity.status(e.getStatus()).body(ApiResponse.error(e.getCode()));
    }

    private Map<String, Object> toBody(TtsJobEntity job) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("job_id", job.getJobId().toString());
        body.put("status", job.getStatus());
        body.put("voice", job.getVoice());
        body.put("text", job.getText());
        body.put("take", job.getTake());
        body.put("created_at", format(job.getCreatedAt()));
        if (job.getStartedAt() != null) body.put("started_at", format(job.getStartedAt()));
        if (job.getFinishedAt() != null) body.put("finished_at", format(job.getFinishedAt()));
        if (TtsJobEntity.COMPLETED.equals(job.getStatus())) {
            body.put("mime_type", job.getMimeType());
            body.put("audio_base64", Base64.getEncoder().encodeToString(job.getAudio()));
            body.put("duration_sec", job.getDurationSec());
            body.put("elapsed_sec", job.getElapsedSec());
        }
        if (TtsJobEntity.FAILED.equals(job.getStatus())) {
            body.put("error", job.getErrorMessage());
        }
        return body;
    }

    private static String format(OffsetDateTime t) {
        return t == null ? null : DateTimeFormatter.ISO_INSTANT.format(t);
    }

    private static String currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "anonymous";
    }
}
