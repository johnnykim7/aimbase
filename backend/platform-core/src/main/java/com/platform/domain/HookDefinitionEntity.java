package com.platform.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * CR-030 PRD-190: 훅 정의 엔티티.
 *
 * 이벤트-매처-타겟으로 구성된 훅을 정의한다.
 * HookRegistry가 이벤트별로 조회하여 HookDispatcher에 제공.
 */
@Entity
@Table(name = "hook_definitions",
        indexes = @Index(name = "idx_hook_definitions_event", columnList = "event"))
public class HookDefinitionEntity {

    @Id
    @Column(length = 100)
    private String id;

    @Column(length = 200, nullable = false)
    private String name;

    @Column(length = 50, nullable = false)
    private String event;

    /** 도구명 매칭 패턴 (glob). null이면 모든 도구에 매칭. Tool 이벤트에서만 사용. */
    @Column(length = 200)
    private String matcher;

    /** 타겟: 내부 훅 = Spring Bean 이름, 외부 훅 = HTTP URL */
    @Column(length = 500, nullable = false)
    private String target;

    /** 타겟 유형: INTERNAL (Bean) 또는 EXTERNAL (HTTP) */
    @Column(name = "target_type", length = 20, nullable = false)
    private String targetType = "INTERNAL";

    /** 외부 훅 타임아웃 (ms). 기본 5000. */
    @Column(name = "timeout_ms")
    private int timeoutMs = 5000;

    /** 실행 순서 (낮을수록 먼저). */
    @Column(name = "exec_order")
    private int execOrder = 0;

    @Column(name = "is_active")
    private boolean isActive = true;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> config;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    // ── Getters / Setters ──

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEvent() { return event; }
    public void setEvent(String event) { this.event = event; }
    public String getMatcher() { return matcher; }
    public void setMatcher(String matcher) { this.matcher = matcher; }
    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    public int getExecOrder() { return execOrder; }
    public void setExecOrder(int execOrder) { this.execOrder = execOrder; }
    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }
    public Map<String, Object> getConfig() { return config; }
    public void setConfig(Map<String, Object> config) { this.config = config; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
