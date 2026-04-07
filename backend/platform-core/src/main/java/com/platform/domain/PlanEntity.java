package com.platform.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CR-033 PRD-222~224: 에이전트 구조적 사고 — Plan 엔티티.
 * 세션 스코프로 관리되며, LLM이 Plan Mode에서 생성하는 계획을 저장한다.
 */
@Entity
@Table(name = "plans", indexes = {
        @Index(name = "idx_plans_session_id", columnList = "session_id"),
        @Index(name = "idx_plans_status", columnList = "status")
})
public class PlanEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "session_id", length = 255, nullable = false)
    private String sessionId;

    @Column(length = 500, nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(length = 50, nullable = false)
    private PlanStatus status = PlanStatus.PLANNING;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<String> goals;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> steps;

    @Type(JsonBinaryType.class)
    @Column(name = "constraints", columnDefinition = "jsonb")
    private List<String> planConstraints;

    @Type(JsonBinaryType.class)
    @Column(name = "verification_result", columnDefinition = "jsonb")
    private Map<String, Object> verificationResult;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    // --- Getters & Setters ---

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public PlanStatus getStatus() { return status; }
    public void setStatus(PlanStatus status) { this.status = status; this.updatedAt = OffsetDateTime.now(); }

    public List<String> getGoals() { return goals; }
    public void setGoals(List<String> goals) { this.goals = goals; }

    public List<Map<String, Object>> getSteps() { return steps; }
    public void setSteps(List<Map<String, Object>> steps) { this.steps = steps; }

    public List<String> getPlanConstraints() { return planConstraints; }
    public void setPlanConstraints(List<String> planConstraints) { this.planConstraints = planConstraints; }

    public Map<String, Object> getVerificationResult() { return verificationResult; }
    public void setVerificationResult(Map<String, Object> verificationResult) { this.verificationResult = verificationResult; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
