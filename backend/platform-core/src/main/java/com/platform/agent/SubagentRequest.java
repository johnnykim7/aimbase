package com.platform.agent;

import java.util.Map;

/**
 * CR-030 PRD-207 + CR-034 PRD-230: 서브에이전트 실행 요청.
 *
 * @param description   에이전트 목적 설명 (3-5 단어)
 * @param prompt        에이전트에게 전달할 작업 프롬프트
 * @param model         사용할 LLM 모델 (null이면 기본값)
 * @param connectionId  LLM 커넥션 ID (null이면 부모 세션의 커넥션)
 * @param isolation     격리 방식 (NONE, WORKTREE)
 * @param runInBackground true면 백그라운드(비동기), false면 포그라운드(동기 대기)
 * @param timeoutMs     타임아웃 밀리초 (0이면 기본값 120_000)
 * @param config        추가 설정 (도구 필터, 스키마 등)
 * @param parentSessionId 부모 세션 ID
 * @param agentType     에이전트 타입 (GENERAL, PLAN, EXPLORE, GUIDE, VERIFICATION)
 */
public record SubagentRequest(
        String description,
        String prompt,
        String model,
        String connectionId,
        IsolationMode isolation,
        boolean runInBackground,
        long timeoutMs,
        Map<String, Object> config,
        String parentSessionId,
        AgentType agentType
) {
    public enum IsolationMode {
        NONE,       // 격리 없이 동일 컨텍스트에서 실행
        WORKTREE    // Git worktree 기반 격리
    }

    /** 기존 9-arg 생성자 호환 (agentType 기본값 GENERAL) */
    public SubagentRequest(String description, String prompt, String model,
                           String connectionId, IsolationMode isolation,
                           boolean runInBackground, long timeoutMs,
                           Map<String, Object> config, String parentSessionId) {
        this(description, prompt, model, connectionId, isolation,
             runInBackground, timeoutMs, config, parentSessionId, AgentType.GENERAL);
    }

    public SubagentRequest {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description is required");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt is required");
        }
        if (isolation == null) isolation = IsolationMode.NONE;
        if (timeoutMs <= 0) timeoutMs = 120_000L;
        if (config == null) config = Map.of();
        if (agentType == null) agentType = AgentType.GENERAL;
    }
}
