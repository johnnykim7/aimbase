package com.platform.agent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CR-030 PRD-207: 서브에이전트 실행 컨텍스트.
 * 부모-자식 관계, 격리 환경, 실행 중 상태를 보유한다.
 */
public class SubagentContext {

    private final String subagentRunId;
    private final String parentSessionId;
    private final String childSessionId;
    private final SubagentRequest request;
    private final WorktreeContext worktreeContext;  // null이면 격리 없음
    private final Map<String, Object> metadata = new ConcurrentHashMap<>();

    public SubagentContext(String subagentRunId,
                           String parentSessionId,
                           String childSessionId,
                           SubagentRequest request,
                           WorktreeContext worktreeContext) {
        this.subagentRunId = subagentRunId;
        this.parentSessionId = parentSessionId;
        this.childSessionId = childSessionId;
        this.request = request;
        this.worktreeContext = worktreeContext;
    }

    public String getSubagentRunId() { return subagentRunId; }
    public String getParentSessionId() { return parentSessionId; }
    public String getChildSessionId() { return childSessionId; }
    public SubagentRequest getRequest() { return request; }
    public WorktreeContext getWorktreeContext() { return worktreeContext; }
    public boolean isIsolated() { return worktreeContext != null; }
    public Map<String, Object> getMetadata() { return metadata; }

    public void putMetadata(String key, Object value) {
        metadata.put(key, value);
    }
}
