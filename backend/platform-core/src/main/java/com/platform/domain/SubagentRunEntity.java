package com.platform.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * CR-030 PRD-207/210: 서브에이전트 실행 기록.
 */
@Entity
@Table(name = "subagent_runs", indexes = {
        @Index(name = "idx_subagent_runs_parent_session", columnList = "parent_session_id"),
        @Index(name = "idx_subagent_runs_status", columnList = "status")
})
public class SubagentRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "parent_session_id", length = 100, nullable = false)
    private String parentSessionId;

    @Column(name = "child_session_id", length = 100)
    private String childSessionId;

    @Column(length = 200)
    private String description;

    @Column(columnDefinition = "text")
    private String prompt;

    @Column(length = 20, nullable = false)
    private String status = "RUNNING";  // RUNNING, COMPLETED, FAILED, TIMEOUT, CANCELLED

    @Column(name = "isolation_mode", length = 20)
    private String isolationMode = "NONE";  // NONE, WORKTREE

    @Column(name = "worktree_path", length = 500)
    private String worktreePath;

    @Column(name = "branch_name", length = 200)
    private String branchName;

    @Column(name = "base_commit", length = 50)
    private String baseCommit;

    @Column(name = "exit_code")
    private int exitCode = -1;

    @Column(columnDefinition = "text")
    private String output;

    @Type(JsonBinaryType.class)
    @Column(name = "structured_data", columnDefinition = "jsonb")
    private Map<String, Object> structuredData;

    @Column(name = "input_tokens")
    private long inputTokens;

    @Column(name = "output_tokens")
    private long outputTokens;

    @Column(name = "duration_ms")
    private long durationMs;

    @Column(columnDefinition = "text")
    private String error;

    @Column(name = "run_in_background")
    private boolean runInBackground;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> config;

    @Column(name = "started_at")
    private OffsetDateTime startedAt = OffsetDateTime.now();

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "timeout_ms")
    private long timeoutMs;

    // --- Getters & Setters ---

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getParentSessionId() { return parentSessionId; }
    public void setParentSessionId(String parentSessionId) { this.parentSessionId = parentSessionId; }

    public String getChildSessionId() { return childSessionId; }
    public void setChildSessionId(String childSessionId) { this.childSessionId = childSessionId; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getIsolationMode() { return isolationMode; }
    public void setIsolationMode(String isolationMode) { this.isolationMode = isolationMode; }

    public String getWorktreePath() { return worktreePath; }
    public void setWorktreePath(String worktreePath) { this.worktreePath = worktreePath; }

    public String getBranchName() { return branchName; }
    public void setBranchName(String branchName) { this.branchName = branchName; }

    public String getBaseCommit() { return baseCommit; }
    public void setBaseCommit(String baseCommit) { this.baseCommit = baseCommit; }

    public int getExitCode() { return exitCode; }
    public void setExitCode(int exitCode) { this.exitCode = exitCode; }

    public String getOutput() { return output; }
    public void setOutput(String output) { this.output = output; }

    public Map<String, Object> getStructuredData() { return structuredData; }
    public void setStructuredData(Map<String, Object> structuredData) { this.structuredData = structuredData; }

    public long getInputTokens() { return inputTokens; }
    public void setInputTokens(long inputTokens) { this.inputTokens = inputTokens; }

    public long getOutputTokens() { return outputTokens; }
    public void setOutputTokens(long outputTokens) { this.outputTokens = outputTokens; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public boolean isRunInBackground() { return runInBackground; }
    public void setRunInBackground(boolean runInBackground) { this.runInBackground = runInBackground; }

    public Map<String, Object> getConfig() { return config; }
    public void setConfig(Map<String, Object> config) { this.config = config; }

    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }

    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime completedAt) { this.completedAt = completedAt; }

    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }
}
