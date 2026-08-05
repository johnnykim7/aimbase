package com.platform.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * CR-137: 영상 판독 job (업로드 → 서버 프레임 추출 → VLM 판독).
 *
 * <p>영상 업로드(~100MB) + ffmpeg 추출 + VLM 6프레임 판독에 수십 초~수 분이 걸려
 * 동기 응답이 불가능하므로 job 등록 → 폴링 구조를 쓴다.
 * CR-133 {@code transcribe_jobs} / CR-120 {@code large_input_jobs} 와 같은 패턴.
 *
 * <p>사진 1장은 이 경로를 타지 않는다 — 기존 {@code chat_attachments}(CR-061) +
 * {@code ChatController.resolveImageBlock} 가 이미 처리한다.
 */
@Entity
@Table(name = "vision_jobs")
public class VisionJobEntity {

    /** status 단방향: PENDING → RUNNING → COMPLETED, 실패 시 FAILED 종착. */
    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false, unique = true)
    private UUID jobId;

    @Column(name = "connection_id", length = 100)
    private String connectionId;

    @Column(length = 500)
    private String filename;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    /** 원본 영상 길이(초) — ffprobe. */
    @Column(name = "duration_sec")
    private Double durationSec;

    /** 실제 추출된 프레임 수 (BIZ-112). */
    @Column(name = "frame_count")
    private Integer frameCount;

    /** 원본 영상 저장 경로 — 재판독·감사를 위해 보존한다. */
    @Column(name = "source_path", columnDefinition = "text")
    private String sourcePath;

    /** 추출 프레임 디렉토리. */
    @Column(name = "frames_path", columnDefinition = "text")
    private String framesPath;

    @Column(columnDefinition = "text")
    private String prompt;

    @Column(length = 30, nullable = false)
    private String status = PENDING;

    @Column(columnDefinition = "text")
    private String result;

    /** structured output(CR-007) 사용 시 파싱 결과. */
    @Type(JsonBinaryType.class)
    @Column(name = "result_json", columnDefinition = "jsonb")
    private Map<String, Object> resultJson;

    @Column(name = "elapsed_sec")
    private Double elapsedSec;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_by", length = 200)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

    // ── getters / setters ────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public UUID getJobId() { return jobId; }
    public void setJobId(UUID jobId) { this.jobId = jobId; }

    public String getConnectionId() { return connectionId; }
    public void setConnectionId(String connectionId) { this.connectionId = connectionId; }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }

    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }

    public Double getDurationSec() { return durationSec; }
    public void setDurationSec(Double durationSec) { this.durationSec = durationSec; }

    public Integer getFrameCount() { return frameCount; }
    public void setFrameCount(Integer frameCount) { this.frameCount = frameCount; }

    public String getSourcePath() { return sourcePath; }
    public void setSourcePath(String sourcePath) { this.sourcePath = sourcePath; }

    public String getFramesPath() { return framesPath; }
    public void setFramesPath(String framesPath) { this.framesPath = framesPath; }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public Map<String, Object> getResultJson() { return resultJson; }
    public void setResultJson(Map<String, Object> resultJson) { this.resultJson = resultJson; }

    public Double getElapsedSec() { return elapsedSec; }
    public void setElapsedSec(Double elapsedSec) { this.elapsedSec = elapsedSec; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }

    public OffsetDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(OffsetDateTime finishedAt) { this.finishedAt = finishedAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
