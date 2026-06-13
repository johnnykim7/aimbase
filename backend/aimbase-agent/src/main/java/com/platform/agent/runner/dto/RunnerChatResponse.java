package com.platform.agent.runner.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * CR-071 Phase 2: 단발 (/v1/chat) 응답 페이로드.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RunnerChatResponse {

    @JsonProperty("run_id")
    private String runId;

    @JsonProperty("model")
    private String model;

    @JsonProperty("content")
    private String content;

    @JsonProperty("tool_calls")
    private List<Map<String, Object>> toolCalls;

    @JsonProperty("usage")
    private Map<String, Object> usage;

    @JsonProperty("finish_reason")
    private String finishReason;

    /** CR-102: CLI 내부 도구 루프 관찰 (가시화 전용 — tool_calls 와 달리 루프 재진입 안 함) */
    @JsonProperty("observed_tool_events")
    private List<Map<String, Object>> observedToolEvents;

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public List<Map<String, Object>> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<Map<String, Object>> toolCalls) { this.toolCalls = toolCalls; }
    public Map<String, Object> getUsage() { return usage; }
    public void setUsage(Map<String, Object> usage) { this.usage = usage; }
    public String getFinishReason() { return finishReason; }
    public void setFinishReason(String finishReason) { this.finishReason = finishReason; }
    public List<Map<String, Object>> getObservedToolEvents() { return observedToolEvents; }
    public void setObservedToolEvents(List<Map<String, Object>> observedToolEvents) { this.observedToolEvents = observedToolEvents; }
}
