package com.platform.agent.runner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * CR-071 Phase 2: ClaudeCliRunner 요청 페이로드.
 * <p>POST /v1/chat 또는 /v1/chat/stream 본문.
 */
public class RunnerChatRequest {

    @JsonProperty("run_id")
    private String runId;

    @JsonProperty("model")
    private String model;

    /** AIMBASE | NATIVE | HYBRID — CR-069 ToolMode. null 이면 AIMBASE. */
    @JsonProperty("tool_mode")
    private String toolMode;

    /** 메시지 목록. {role, content} 형태. */
    @JsonProperty("messages")
    private List<Map<String, Object>> messages;

    @JsonProperty("system_prompt_override")
    private String systemPromptOverride;

    @JsonProperty("config_dir")
    private String configDir;

    @JsonProperty("max_tokens")
    private Integer maxTokens;

    @JsonProperty("fork_session")
    private Boolean forkSession;

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getToolMode() { return toolMode; }
    public void setToolMode(String toolMode) { this.toolMode = toolMode; }
    public List<Map<String, Object>> getMessages() { return messages; }
    public void setMessages(List<Map<String, Object>> messages) { this.messages = messages; }
    public String getSystemPromptOverride() { return systemPromptOverride; }
    public void setSystemPromptOverride(String s) { this.systemPromptOverride = s; }
    public String getConfigDir() { return configDir; }
    public void setConfigDir(String configDir) { this.configDir = configDir; }
    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
    public Boolean getForkSession() { return forkSession; }
    public void setForkSession(Boolean forkSession) { this.forkSession = forkSession; }
}
