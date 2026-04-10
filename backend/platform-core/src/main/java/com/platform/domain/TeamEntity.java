package com.platform.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CR-039 PRD-265/266: Swarm 팀 엔티티.
 * 세션 스코프 휘발성 — 세션 종료 시 DISSOLVED 전환.
 */
@Entity
@Table(name = "teams", indexes = {
        @Index(name = "idx_teams_session_id", columnList = "session_id"),
        @Index(name = "idx_teams_status", columnList = "status"),
        @Index(name = "idx_teams_session_status", columnList = "session_id, status")
})
public class TeamEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "objective", columnDefinition = "TEXT")
    private String objective;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "ACTIVE";

    @Type(JsonBinaryType.class)
    @Column(name = "members", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> members;

    @Column(name = "result_summary", columnDefinition = "TEXT")
    private String resultSummary;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "dissolved_at")
    private OffsetDateTime dissolvedAt;

    // ── Getters / Setters ──

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getObjective() { return objective; }
    public void setObjective(String objective) { this.objective = objective; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public List<Map<String, Object>> getMembers() { return members; }
    public void setMembers(List<Map<String, Object>> members) { this.members = members; }

    public String getResultSummary() { return resultSummary; }
    public void setResultSummary(String resultSummary) { this.resultSummary = resultSummary; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getDissolvedAt() { return dissolvedAt; }
    public void setDissolvedAt(OffsetDateTime dissolvedAt) { this.dissolvedAt = dissolvedAt; }
}
