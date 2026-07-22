package com.platform.agent.runner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class RunnerCancelRequest {
    @JsonProperty("run_id")
    private String runId;

    /**
     * CR-121: true 면 {@code runId} 를 접두사로 보고, 그 접두사로 시작하는 모든 세션의 워커를 일괄 종료한다
     * (LARGE_INPUT 청크/재시도 sessionId 가 제각각인 잔여 회수용). 기본 false.
     */
    @JsonProperty("prefix")
    private boolean prefix;

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }

    public boolean isPrefix() { return prefix; }
    public void setPrefix(boolean prefix) { this.prefix = prefix; }
}
