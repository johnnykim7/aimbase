package com.platform.agent.runner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class RunnerCancelRequest {
    @JsonProperty("run_id")
    private String runId;

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
}
