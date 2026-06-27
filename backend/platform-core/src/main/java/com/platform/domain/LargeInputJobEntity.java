package com.platform.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CR-120: 대용량 입력 전수 분석 작업(job) 상태.
 *
 * <p>{@code LARGE_INPUT} 스텝 1회 실행 = job 1개. 자율주행 밖에서 결정론적으로 분해한 청크 목록과
 * 청크별 처리 결과, 계층 Reduce 트리, 누락 검증 리포트를 한 테이블 JSONB 컬럼에 적재한다
 * (WorkflowRunEventEntity 의 {@code @Type(JsonBinaryType)} 패턴 재사용).
 *
 * <p>본질: 32MB(Anthropic API 요청 물리한계)를 애초에 안 치게 청크 단위로 전수 처리하고,
 * 누락을 숨기지 않는다(CR-119 철학) — coverage_report 에 처리/실패 청크를 정직하게 남긴다.
 */
@Entity
@Table(name = "large_input_jobs", indexes = {
        @Index(name = "idx_lij_run", columnList = "run_id"),
        @Index(name = "idx_lij_status", columnList = "status")
})
public class LargeInputJobEntity {

    /** 분해→처리→Reduce→검증→완료 의 단방향 상태. FAILED 는 어느 단계에서든 종착. */
    public enum Status {
        DECOMPOSING, PROCESSING, REDUCING, VERIFYING, COMPLETED, FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** job 외부 식별자 — 결과 Map 으로 반환해 운영이 추적. id(PK)와 분리해 외부 UUID 주입 충돌 회피. */
    @Column(name = "job_id", nullable = false, unique = true, columnDefinition = "uuid")
    private UUID jobId;

    @Column(name = "run_id", nullable = false, columnDefinition = "uuid")
    private UUID runId;

    @Column(name = "step_id", length = 100)
    private String stepId;

    @Column(name = "analysis_action", length = 64)
    private String analysisAction;

    /** source_file attachment_id 또는 인라인 입력 식별자(있으면). 추적·디버깅용. */
    @Column(name = "source_ref", length = 255)
    private String sourceRef;

    @Column(name = "status", length = 30, nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "total_chunks")
    private Integer totalChunks;

    @Column(name = "completed", nullable = false)
    private int completed = 0;

    @Column(name = "failed", nullable = false)
    private int failed = 0;

    /** 분해된 청크 메타 목록 (LargeInputChunk 직렬화). */
    @Type(JsonBinaryType.class)
    @Column(name = "chunks", columnDefinition = "jsonb")
    private List<Map<String, Object>> chunks;

    /** 청크별 처리 결과(map 단계 출력). 증분 적재. */
    @Type(JsonBinaryType.class)
    @Column(name = "chunk_results", columnDefinition = "jsonb")
    private List<Map<String, Object>> chunkResults;

    /** 계층 Reduce 트리(레벨별 묶음 정리 기록). */
    @Type(JsonBinaryType.class)
    @Column(name = "reduce_tree", columnDefinition = "jsonb")
    private Map<String, Object> reduceTree;

    /** 누락 검증 리포트(total/completed/failed/failed_chunks/coverage_ratio/full_coverage). */
    @Type(JsonBinaryType.class)
    @Column(name = "coverage_report", columnDefinition = "jsonb")
    private Map<String, Object> coverageReport;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = OffsetDateTime.now(); }

    public Long getId() { return id; }
    public UUID getJobId() { return jobId; }
    public void setJobId(UUID jobId) { this.jobId = jobId; }
    public UUID getRunId() { return runId; }
    public void setRunId(UUID runId) { this.runId = runId; }
    public String getStepId() { return stepId; }
    public void setStepId(String stepId) { this.stepId = stepId; }
    public String getAnalysisAction() { return analysisAction; }
    public void setAnalysisAction(String analysisAction) { this.analysisAction = analysisAction; }
    public String getSourceRef() { return sourceRef; }
    public void setSourceRef(String sourceRef) { this.sourceRef = sourceRef; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Integer getTotalChunks() { return totalChunks; }
    public void setTotalChunks(Integer totalChunks) { this.totalChunks = totalChunks; }
    public int getCompleted() { return completed; }
    public void setCompleted(int completed) { this.completed = completed; }
    public int getFailed() { return failed; }
    public void setFailed(int failed) { this.failed = failed; }
    public List<Map<String, Object>> getChunks() { return chunks; }
    public void setChunks(List<Map<String, Object>> chunks) { this.chunks = chunks; }
    public List<Map<String, Object>> getChunkResults() { return chunkResults; }
    public void setChunkResults(List<Map<String, Object>> chunkResults) { this.chunkResults = chunkResults; }
    public Map<String, Object> getReduceTree() { return reduceTree; }
    public void setReduceTree(Map<String, Object> reduceTree) { this.reduceTree = reduceTree; }
    public Map<String, Object> getCoverageReport() { return coverageReport; }
    public void setCoverageReport(Map<String, Object> coverageReport) { this.coverageReport = coverageReport; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
