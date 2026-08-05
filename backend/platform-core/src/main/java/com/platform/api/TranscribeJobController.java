package com.platform.api;

import com.platform.domain.TranscribeJobEntity;
import com.platform.speech.SpeechException;
import com.platform.speech.TranscribeJobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * CR-133: 회의녹음 배치 전사 API.
 *
 * <p>위젯 STT({@link ChatSttController}, 60초/120초 제한)와 <b>경로를 분리</b>한다.
 * 그 제한은 짧은 발화를 보호하려고 일부러 건 값이라 회의녹음 때문에 풀면 안 된다.
 *
 * <pre>
 *   POST /api/v1/transcribe-jobs        → 즉시 job_id 반환 (전사는 백그라운드)
 *   GET  /api/v1/transcribe-jobs/{id}   → 상태·결과 폴링
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/transcribe-jobs")
@Tag(name = "Transcribe Jobs", description = "CR-133 회의녹음 배치 전사")
public class TranscribeJobController {

    private static final Logger log = LoggerFactory.getLogger(TranscribeJobController.class);

    /**
     * 회의녹음 상한 100MB.
     *
     * CR-137 Phase 0 정정: 기존 1GB("4시간 분량" 근거)는 앞단에서 이미 막혀 도달 불가였다.
     * nginx client_max_body_size 100m → Spring multipart(구 50MB) 순으로 먼저 걸리므로
     * 이 검사가 실행될 일이 없었다. 상한 3계층을 100MB 로 맞춘다.
     * 실제 소요: 1시간 회의(m4a/opus) 50~120MB. 무손실 WAV 가 아니면 충분하다.
     * 더 큰 파일이 필요해지면 nginx → application.yml → 여기 순서로 함께 올릴 것.
     */
    private static final long MAX_SIZE_BYTES = 100L * 1024L * 1024L;

    private final TranscribeJobService jobService;

    public TranscribeJobController(TranscribeJobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "회의녹음 업로드 → 전사 job 등록 (비동기)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submit(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "language", required = false, defaultValue = "auto") String language,
            @RequestParam(value = "connection_id", required = false) String connectionId) {

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("file is required"));
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            return ResponseEntity.status(413)
                    .body(ApiResponse.error("file too large: " + file.getSize() + " > " + MAX_SIZE_BYTES));
        }

        try {
            TranscribeJobEntity job = jobService.submit(
                    file.getBytes(),
                    file.getContentType(),
                    file.getOriginalFilename(),
                    language,
                    connectionId,
                    currentUser());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("job_id", job.getJobId().toString());
            body.put("status", job.getStatus());
            body.put("filename", job.getFilename());
            body.put("size_bytes", job.getSizeBytes());
            return ResponseEntity.accepted().body(ApiResponse.ok(body));

        } catch (SpeechException e) {
            return mapSpeechError(e);
        } catch (java.io.IOException e) {
            log.error("[CR-133] 업로드 읽기 실패: {}", e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponse.error("invalid multipart: " + e.getMessage()));
        }
    }

    @GetMapping("/{jobId}")
    @Operation(summary = "전사 job 상태·결과 조회")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(
            @PathVariable("jobId") String jobId,
            @RequestParam(value = "include_segments", defaultValue = "false") boolean includeSegments) {

        UUID uuid;
        try {
            uuid = UUID.fromString(jobId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("invalid job_id: " + jobId));
        }

        return jobService.find(uuid)
                .map(job -> ResponseEntity.ok(ApiResponse.ok(toBody(job, includeSegments))))
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(ApiResponse.error("job not found: " + jobId)));
    }

    private Map<String, Object> toBody(TranscribeJobEntity job, boolean includeSegments) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("job_id", job.getJobId().toString());
        body.put("status", job.getStatus());
        body.put("filename", job.getFilename());
        body.put("size_bytes", job.getSizeBytes());
        body.put("created_at", job.getCreatedAt());
        if (job.getStartedAt() != null) body.put("started_at", job.getStartedAt());
        if (job.getFinishedAt() != null) body.put("finished_at", job.getFinishedAt());
        if (job.getText() != null) body.put("text", job.getText());
        if (job.getDetectedLang() != null) body.put("language", job.getDetectedLang());
        if (job.getDurationSec() != null) body.put("duration_sec", job.getDurationSec());
        if (job.getElapsedSec() != null) body.put("elapsed_sec", job.getElapsedSec());
        if (job.getErrorMessage() != null) body.put("error", job.getErrorMessage());
        // 세그먼트는 1시간 회의면 수백 건이라 기본 제외하고 요청 시에만 싣는다.
        if (includeSegments && job.getSegments() != null) body.put("segments", job.getSegments());
        return body;
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> mapSpeechError(SpeechException e) {
        return switch (e.getCode()) {
            case PROVIDER_UNAVAILABLE -> ResponseEntity.status(503).body(ApiResponse.error(e.getMessage()));
            case TIMEOUT -> ResponseEntity.status(504).body(ApiResponse.error(e.getMessage()));
            case UPSTREAM_ERROR -> ResponseEntity.status(502).body(ApiResponse.error(e.getMessage()));
        };
    }

    private String currentUser() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        return auth != null ? auth.getName() : "anonymous";
    }
}
