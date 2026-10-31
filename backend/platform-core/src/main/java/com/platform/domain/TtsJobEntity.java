package com.platform.domain;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * CR-166: 같은 목소리 음성 생성 작업.
 *
 * <p>상태 전이는 T1-5 TtsJob. RUNNING 에서 PENDING 으로 되돌리는 것은 api 를 다시 띄워
 * 차례에서 빠진 작업을 다시 넣을 때뿐이다.
 */
@Entity
@Table(name = "tts_jobs")
public class TtsJobEntity {

    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";

    public static final Set<String> ACTIVE = Set.of(PENDING, RUNNING);
    public static final Set<String> FINISHED = Set.of(COMPLETED, FAILED);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false, unique = true)
    private UUID jobId;

    @Column(length = 100, nullable = false)
    private String voice;

    @Column(columnDefinition = "text", nullable = false)
    private String text;

    @Column(length = 30, nullable = false)
    private String status = PENDING;

    @Column(columnDefinition = "bytea")
    private byte[] audio;

    @Column(name = "mime_type", length = 50)
    private String mimeType;

    @Column(name = "duration_sec")
    private Double durationSec;

    @Column(name = "elapsed_sec")
    private Double elapsedSec;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_by", length = 200)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    public boolean isActive() { return ACTIVE.contains(status); }

    public Long getId() { return id; }
    public UUID getJobId() { return jobId; }
    public void setJobId(UUID jobId) { this.jobId = jobId; }
    public String getVoice() { return voice; }
    public void setVoice(String voice) { this.voice = voice; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public byte[] getAudio() { return audio; }
    public void setAudio(byte[] audio) { this.audio = audio; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    public Double getDurationSec() { return durationSec; }
    public void setDurationSec(Double durationSec) { this.durationSec = durationSec; }
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
}
