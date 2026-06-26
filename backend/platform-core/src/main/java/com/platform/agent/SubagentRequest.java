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
 * @param workflowRunId  CR-102: AGENT_CALL 스텝에서 실행될 때의 워크플로우 run ID (이벤트 타임라인 연결). 비워크플로우 경로면 null.
 * @param workflowStepId CR-102: AGENT_CALL 스텝 ID
 * @param resumeSessionId CR-106: 지정 시 SubagentRunner 가 새 UUID 대신 이 값을 childSessionId 로 사용 →
 *        같은 CLI run_id 로 Worker 재사용 + {@code --resume} 이어하기. timeout 류 retry 멱등화 전용.
 *        null 이면 기존대로 매 호출 새 childSessionId 발급(메인 대화 서브에이전트 격리 보존).
 * @param workspacePath CR-107 후속: 부모 run 의 격리 workspace 절대경로. SubagentRunner 가 ChatRequest.workingDirectory 로
 *        전파 → OrchestratorEngine.resolveWorkspace 가 새 childSessionId 에 묶어, 서브에이전트가 부모 run 과 같은 workspace 를 본다.
 *        null 이면 기존 동작(세션 workspaceRef → tenant/project 폴백). TOOL_CALL 이 쓴 파일을 AGENT_CALL 이 못 보던 단절 해소.
 * @param requireTextOutput CR-117: true(기본) 면 turn 이 텍스트/structured 둘 다 비면 FAILED 로 판정(빈응답=실패).
 *        false 면 도구만 쓰고 끝내는 에이전트(빈 텍스트 정상)를 허용 → 빈응답도 COMPLETED 통과. 빈응답 자식 재시도/감지 정책의 토글.
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
        AgentType agentType,
        String workflowRunId,
        String workflowStepId,
        String resumeSessionId,
        String workspacePath,
        boolean requireTextOutput
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
             runInBackground, timeoutMs, config, parentSessionId, AgentType.GENERAL, null, null, null, null, true);
    }

    /** 기존 10-arg 생성자 호환 (CR-102 워크플로우 연결 키 없음) */
    public SubagentRequest(String description, String prompt, String model,
                           String connectionId, IsolationMode isolation,
                           boolean runInBackground, long timeoutMs,
                           Map<String, Object> config, String parentSessionId,
                           AgentType agentType) {
        this(description, prompt, model, connectionId, isolation,
             runInBackground, timeoutMs, config, parentSessionId, agentType, null, null, null, null, true);
    }

    /** 기존 12-arg 생성자 호환 (CR-102 워크플로우 연결 키 포함, CR-106 resumeSessionId 없음) */
    public SubagentRequest(String description, String prompt, String model,
                           String connectionId, IsolationMode isolation,
                           boolean runInBackground, long timeoutMs,
                           Map<String, Object> config, String parentSessionId,
                           AgentType agentType, String workflowRunId, String workflowStepId) {
        this(description, prompt, model, connectionId, isolation,
             runInBackground, timeoutMs, config, parentSessionId, agentType,
             workflowRunId, workflowStepId, null, null, true);
    }

    /** 기존 13-arg 생성자 호환 (CR-106 resumeSessionId 포함, CR-107 후속 workspacePath 없음) */
    public SubagentRequest(String description, String prompt, String model,
                           String connectionId, IsolationMode isolation,
                           boolean runInBackground, long timeoutMs,
                           Map<String, Object> config, String parentSessionId,
                           AgentType agentType, String workflowRunId, String workflowStepId,
                           String resumeSessionId) {
        this(description, prompt, model, connectionId, isolation,
             runInBackground, timeoutMs, config, parentSessionId, agentType,
             workflowRunId, workflowStepId, resumeSessionId, null, true);
    }

    /** 기존 14-arg 생성자 호환 (CR-107 workspacePath 포함, CR-117 requireTextOutput 기본 true) */
    public SubagentRequest(String description, String prompt, String model,
                           String connectionId, IsolationMode isolation,
                           boolean runInBackground, long timeoutMs,
                           Map<String, Object> config, String parentSessionId,
                           AgentType agentType, String workflowRunId, String workflowStepId,
                           String resumeSessionId, String workspacePath) {
        this(description, prompt, model, connectionId, isolation,
             runInBackground, timeoutMs, config, parentSessionId, agentType,
             workflowRunId, workflowStepId, resumeSessionId, workspacePath, true);
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
