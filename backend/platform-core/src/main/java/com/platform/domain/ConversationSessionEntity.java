package com.platform.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "conversation_sessions")
public class ConversationSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "session_id", length = 100, unique = true, nullable = false)
    private String sessionId;

    @Column(name = "user_id", length = 100)
    private String userId;

    @Column(length = 500)
    private String title;

    @Column(length = 100)
    private String model;

    @Column(name = "message_count")
    private int messageCount = 0;

    @Column(name = "total_tokens")
    private long totalTokens = 0;

    @Column(name = "created_at")
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "summary_text", columnDefinition = "text")
    private String summaryText;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    // --- CR-029: Session Meta 확장 ---

    @Column(name = "scope_type", length = 20)
    private String scopeType = "chat";

    @Column(name = "runtime_kind", length = 20)
    private String runtimeKind;

    @Column(name = "workspace_ref", length = 500)
    private String workspaceRef;

    @Column(name = "persistent_session")
    private boolean persistentSession = false;

    @Column(name = "summary_version")
    private int summaryVersion = 0;

    @Column(name = "context_recipe_id", length = 100)
    private String contextRecipeId;

    @Type(JsonBinaryType.class)
    @Column(name = "last_tool_chain", columnDefinition = "jsonb")
    private Map<String, Object> lastToolChain;

    @Column(name = "app_id", length = 100)
    private String appId;

    @Column(name = "project_id", length = 100)
    private String projectId;

    @Column(name = "parent_session_id", length = 100)
    private String parentSessionId;

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    // --- 기존 Getters & Setters ---

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getMessageCount() { return messageCount; }
    public void setMessageCount(int messageCount) { this.messageCount = messageCount; }
    public long getTotalTokens() { return totalTokens; }
    public void setTotalTokens(long totalTokens) { this.totalTokens = totalTokens; }
    public String getSummaryText() { return summaryText; }
    public void setSummaryText(String summaryText) { this.summaryText = summaryText; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    // --- CR-029 Getters & Setters ---

    public String getScopeType() { return scopeType; }
    public void setScopeType(String scopeType) { this.scopeType = scopeType; }
    public String getRuntimeKind() { return runtimeKind; }
    public void setRuntimeKind(String runtimeKind) { this.runtimeKind = runtimeKind; }
    public String getWorkspaceRef() { return workspaceRef; }
    public void setWorkspaceRef(String workspaceRef) { this.workspaceRef = workspaceRef; }
    public boolean isPersistentSession() { return persistentSession; }
    public void setPersistentSession(boolean persistentSession) { this.persistentSession = persistentSession; }
    public int getSummaryVersion() { return summaryVersion; }
    public void setSummaryVersion(int summaryVersion) { this.summaryVersion = summaryVersion; }
    public String getContextRecipeId() { return contextRecipeId; }
    public void setContextRecipeId(String contextRecipeId) { this.contextRecipeId = contextRecipeId; }
    public Map<String, Object> getLastToolChain() { return lastToolChain; }
    public void setLastToolChain(Map<String, Object> lastToolChain) { this.lastToolChain = lastToolChain; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getParentSessionId() { return parentSessionId; }
    public void setParentSessionId(String parentSessionId) { this.parentSessionId = parentSessionId; }
}
