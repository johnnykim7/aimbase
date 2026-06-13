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

    /**
     * CR-104: 이 호출에서 CLI 가 사용 가능한 도구 이름 목록 (원본 도구명, prefix 없음).
     * <p>서버 측({@code ClaudeCliRunnerClient}) 이 {@code LLMRequest.tools()}(= API 경로가 모델에
     * 전달하는 {@code getToolDefs(toolFilter)} 와 동일 집합)를 그대로 실어 보낸다.
     * Runner 는 이를 {@code mcp__aimbase-server__<tool>} 형식으로 변환해 CLI {@code --allowedTools} 로 주입,
     * CLI 가 가져가는 도구 = API tools 목록이 되게 한다 (불변식).
     * <p>null/빈 목록이면 제한 미적용 — 서버 MCP endpoint 가 노출하는 전체(CLI_EXPOSED)를 그대로 사용.
     */
    @JsonProperty("allowed_tools")
    private List<String> allowedTools;

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
    public List<String> getAllowedTools() { return allowedTools; }
    public void setAllowedTools(List<String> allowedTools) { this.allowedTools = allowedTools; }
}
