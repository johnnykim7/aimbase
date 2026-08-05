package com.platform.api;

import com.platform.attachment.AttachmentException;
import com.platform.attachment.MimeValidator;
import com.platform.domain.VisionJobEntity;
import com.platform.tenant.TenantContext;
import com.platform.vision.VisionJobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * CR-137: 영상 판독 job API — 현장에서 찍은 영상을 올리면 서버가 프레임을 뽑아 VLM 에 태운다.
 *
 * <pre>
 *   POST /api/v1/vision-jobs        → 즉시 job_id 반환 (추출·판독은 백그라운드)
 *   GET  /api/v1/vision-jobs/{id}   → 상태·결과 폴링
 * </pre>
 *
 * <p><b>촬영 UI 는 소비앱이 만든다.</b> aimbase 는 업로드 수신·프레임 추출·판독만 담당한다.
 * 사진 1장은 이 API 가 아니라 기존 {@code POST /api/v1/chat/attachments} 경로를 쓴다
 * (CR-061 + CR-136 으로 이미 동작).
 */
@RestController
@RequestMapping("/api/v1/vision-jobs")
@Tag(name = "Vision Jobs", description = "CR-137 영상 프레임 추출 + VLM 판독")
public class VisionJobController {

    private static final Logger log = LoggerFactory.getLogger(VisionJobController.class);

    /**
     * 업로드 상한 100MB (BIZ-113).
     * 앞단 nginx {@code client_max_body_size 100m} / Spring multipart 100MB 와 맞춘 값이다.
     * 셋 중 하나만 바꾸면 어긋난 구간에서 엉뚱한 에러가 나므로 함께 조정할 것.
     */
    private static final long MAX_SIZE_BYTES = 100L * 1024L * 1024L;

    private final VisionJobService jobService;
    private final MimeValidator mimeValidator;

    @Value("${vision.work-dir:/data/aimbase/vision-jobs}")
    private String workDirRoot;

    public VisionJobController(VisionJobService jobService, MimeValidator mimeValidator) {
        this.jobService = jobService;
        this.mimeValidator = mimeValidator;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "영상 업로드 → 프레임 추출 + VLM 판독 job 등록 (비동기)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submit(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "connection_id", required = false) String connectionId,
            @RequestParam(value = "frames", required = false) Integer frames,
            @RequestParam(value = "prompt", required = false) String prompt) {

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("file is required"));
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            return ResponseEntity.status(413).body(ApiResponse.error(
                    "file too large: " + file.getSize() + " > " + MAX_SIZE_BYTES));
        }

        // 매직넘버로 실제 영상인지 확인 — 확장자·Content-Type 은 위조 가능하다.
        String mediaType;
        try {
            byte[] head = new byte[(int) Math.min(16, file.getSize())];
            try (var in = file.getInputStream()) {
                int read = in.read(head);
                if (read < head.length) {
                    return ResponseEntity.badRequest().body(ApiResponse.error("file is too short"));
                }
            }
            mediaType = mimeValidator.detect(head);
            mimeValidator.assertMatchesContentType(mediaType, file.getContentType());
        } catch (AttachmentException e) {
            return ResponseEntity.status(e.getStatusCode()).body(ApiResponse.error(e.getReason()));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("failed to read file: " + e.getMessage()));
        }

        if (!MimeValidator.isVideo(mediaType)) {
            return ResponseEntity.badRequest().body(ApiResponse.error(
                    "not a video (detected=" + mediaType + ") — "
                            + "use POST /api/v1/chat/attachments for images and PDFs"));
        }

        // 원본을 디스크에 저장한다. 메모리에 들고 있으면 100MB × 동시요청이 그대로 힙을 먹는다.
        // 재판독·감사를 위해 판독 후에도 보존한다(BIZ-114, TTL GC 대상).
        UUID stagingId = UUID.randomUUID();
        Path videoPath;
        try {
            String tenantId = TenantContext.getTenantId();
            Path dir = Path.of(workDirRoot, tenantId == null ? "_" : tenantId, stagingId.toString());
            Files.createDirectories(dir);
            videoPath = dir.resolve("source" + extensionOf(mediaType));
            try (var in = file.getInputStream()) {
                Files.copy(in, videoPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("[CR-137] 원본 저장 실패: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(
                    ApiResponse.error("failed to store video: " + e.getMessage()));
        }

        VisionJobEntity job = jobService.submit(
                videoPath, mediaType, file.getOriginalFilename(), file.getSize(),
                frames, prompt, connectionId, currentUser());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("job_id", job.getJobId());
        body.put("status", job.getStatus());
        body.put("filename", job.getFilename());
        body.put("mime_type", job.getMimeType());
        body.put("size_bytes", job.getSizeBytes());
        body.put("frames", job.getFrameCount());
        return ResponseEntity.accepted().body(ApiResponse.ok(body));
    }

    @GetMapping("/{jobId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "영상 판독 job 상태·결과 조회 (폴링)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(@PathVariable("jobId") String jobId) {
        UUID uuid;
        try {
            uuid = UUID.fromString(jobId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("invalid job_id"));
        }

        return jobService.find(uuid)
                .map(j -> ResponseEntity.ok(ApiResponse.<Map<String, Object>>ok(toDto(j))))
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(ApiResponse.error("job not found: " + jobId)));
    }

    private static Map<String, Object> toDto(VisionJobEntity j) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("job_id", j.getJobId());
        m.put("status", j.getStatus());
        m.put("filename", j.getFilename());
        m.put("mime_type", j.getMimeType());
        m.put("size_bytes", j.getSizeBytes());
        m.put("duration_sec", j.getDurationSec());
        m.put("frame_count", j.getFrameCount());
        m.put("result", j.getResult());
        m.put("result_json", j.getResultJson());
        m.put("elapsed_sec", j.getElapsedSec());
        m.put("error_message", j.getErrorMessage());
        m.put("created_at", j.getCreatedAt());
        m.put("started_at", j.getStartedAt());
        m.put("finished_at", j.getFinishedAt());
        return m;
    }

    private static String extensionOf(String mediaType) {
        return switch (mediaType) {
            case MimeValidator.MP4 -> ".mp4";
            case MimeValidator.MOV -> ".mov";
            case MimeValidator.WEBM -> ".webm";
            case MimeValidator.AVI -> ".avi";
            default -> ".bin";
        };
    }

    private static String currentUser() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        return auth == null ? null : auth.getName();
    }
}
