package com.platform.domain;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * CR-179: 위젯 사용자 한 사람의 내 선호 글, 자동 기억 켬, 마지막에 선택한 모델과 깊게 생각하기(PRD-440, PRD-442).
 * 주인은 위젯 토큰 user_ref 다(BIZ-180).
 */
@Entity
@Table(name = "widget_user_preferences")
public class WidgetUserPreferenceEntity {

    @Id
    @Column(name = "user_ref", length = 200)
    private String userRef;

    @Column(columnDefinition = "text")
    private String instructions;

    @Column(name = "memory_enabled", nullable = false)
    private boolean memoryEnabled = true;

    @Column(name = "last_model", length = 100)
    private String lastModel;

    /** null 은 사용자가 바꾼 적이 없다는 뜻이라 위젯 설정 기본값을 쓴다(BIZ-185). */
    @Column(name = "last_thinking")
    private Boolean lastThinking;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    protected WidgetUserPreferenceEntity() {}

    public WidgetUserPreferenceEntity(String userRef) {
        this.userRef = userRef;
    }

    @PreUpdate
    void onUpdate() { this.updatedAt = OffsetDateTime.now(); }

    public String getUserRef() { return userRef; }
    public String getInstructions() { return instructions; }
    public void setInstructions(String instructions) { this.instructions = instructions; }
    public boolean isMemoryEnabled() { return memoryEnabled; }
    public void setMemoryEnabled(boolean memoryEnabled) { this.memoryEnabled = memoryEnabled; }
    public String getLastModel() { return lastModel; }
    public void setLastModel(String lastModel) { this.lastModel = lastModel; }
    public Boolean getLastThinking() { return lastThinking; }
    public void setLastThinking(Boolean lastThinking) { this.lastThinking = lastThinking; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
