package com.platform.orchestrator;

import com.platform.llm.model.UnifiedMessage;
import com.platform.tool.SkillRef;
import com.platform.tool.ToolFilterContext;

import java.util.List;
import java.util.Map;

/**
 * @param toolFilter          도구 필터링 컨텍스트 (null이면 전체 도구 노출)
 * @param toolChoice          도구 선택 전략 (null/"auto": 자율, "none": 미사용, "required": 필수, "{tool_name}": 강제)
 * @param responseFormat      구조화된 출력 요청 (CR-007). null이면 일반 텍스트 응답.
 * @param connectionGroupId   커넥션 그룹 ID (CR-015). 그룹 내 전략에 따라 커넥션 선택 + 자동 폴백.
 * @param workingDirectory    작업 디렉토리 (CR-045). 세션 최초 요청 시에만 의미. 세션 메타에 저장되어 재개 시 복원.
 * @param workflowRunId       CR-102: 워크플로우 run ID — AGENT_CALL 서브에이전트 도구 루프 이벤트를 run 타임라인에 연결. null이면 비워크플로우(채팅) 경로.
 * @param workflowStepId      CR-102: 워크플로우 스텝 ID (workflowRunId 와 함께 전파)
 * @param subagentRunId       CR-102: 서브에이전트 run ID — 멀티에이전트 병렬 시 이벤트 구분
 * @param toolModeOverride    CR-128(갭B): 요청 단위 tool_mode 오버라이드(NATIVE/AIMBASE/HYBRID). CLI 어댑터 전용.
 *                            null 이면 커넥션 config 값 사용(기존 동작).
 * @param systemPromptOverride CR-128(갭B): 요청 단위 system_prompt_override. CLI 어댑터 전용.
 *                            null 이면 커넥션 config 값 사용(기존 동작).
 * @param skillSet           CR-147: 워크플로우 단계에 지정한 스킬과 판(BIZ-121). 도구 호출의 ToolContext 로 넘긴다.
 *                            null 이면 지정 없음(기존 동작).
 */
public record ChatRequest(
        String model,
        String sessionId,
        List<UnifiedMessage> messages,
        boolean stream,
        boolean actionsEnabled,
        String userId,
        String ragSourceId,
        String connectionId,
        ToolFilterContext toolFilter,
        String toolChoice,
        ResponseFormat responseFormat,
        String connectionGroupId,
        String workingDirectory,
        String workflowRunId,
        String workflowStepId,
        String subagentRunId,
        String toolModeOverride,
        String systemPromptOverride,
        List<SkillRef> skillSet
) {
    public ChatRequest(String model, List<UnifiedMessage> messages) {
        this(model, null, messages, false, false, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** 기존 13-arg 호환 (CR-102 워크플로우 연결 키 없음, CR-128 override 없음) */
    public ChatRequest(String model, String sessionId, List<UnifiedMessage> messages,
                       boolean stream, boolean actionsEnabled, String userId,
                       String ragSourceId, String connectionId,
                       ToolFilterContext toolFilter, String toolChoice,
                       ResponseFormat responseFormat, String connectionGroupId,
                       String workingDirectory) {
        this(model, sessionId, messages, stream, actionsEnabled, userId, ragSourceId, connectionId,
                toolFilter, toolChoice, responseFormat, connectionGroupId, workingDirectory,
                null, null, null, null, null, null);
    }

    /** CR-128(갭B): 13-arg + tool_mode/system_prompt override (ChatController 채팅 경로용) */
    public ChatRequest(String model, String sessionId, List<UnifiedMessage> messages,
                       boolean stream, boolean actionsEnabled, String userId,
                       String ragSourceId, String connectionId,
                       ToolFilterContext toolFilter, String toolChoice,
                       ResponseFormat responseFormat, String connectionGroupId,
                       String workingDirectory,
                       String toolModeOverride, String systemPromptOverride) {
        this(model, sessionId, messages, stream, actionsEnabled, userId, ragSourceId, connectionId,
                toolFilter, toolChoice, responseFormat, connectionGroupId, workingDirectory,
                null, null, null, toolModeOverride, systemPromptOverride, null);
    }

    /** 기존 16-arg 호환 (CR-102 워크플로우 연결 키 포함, CR-128 override 없음 — SubagentRunner 등) */
    public ChatRequest(String model, String sessionId, List<UnifiedMessage> messages,
                       boolean stream, boolean actionsEnabled, String userId,
                       String ragSourceId, String connectionId,
                       ToolFilterContext toolFilter, String toolChoice,
                       ResponseFormat responseFormat, String connectionGroupId,
                       String workingDirectory,
                       String workflowRunId, String workflowStepId, String subagentRunId) {
        this(model, sessionId, messages, stream, actionsEnabled, userId, ragSourceId, connectionId,
                toolFilter, toolChoice, responseFormat, connectionGroupId, workingDirectory,
                workflowRunId, workflowStepId, subagentRunId, null, null, null);
    }

    /** 기존 호환용 생성자 (toolFilter/toolChoice/responseFormat/connectionGroupId 없음) */
    public ChatRequest(String model, String sessionId, List<UnifiedMessage> messages,
                       boolean stream, boolean actionsEnabled, String userId,
                       String ragSourceId, String connectionId) {
        this(model, sessionId, messages, stream, actionsEnabled, userId, ragSourceId, connectionId,
                null, null, null, null, null);
    }

    /** 기존 호환용 생성자 (responseFormat/connectionGroupId 없음) */
    public ChatRequest(String model, String sessionId, List<UnifiedMessage> messages,
                       boolean stream, boolean actionsEnabled, String userId,
                       String ragSourceId, String connectionId,
                       ToolFilterContext toolFilter, String toolChoice) {
        this(model, sessionId, messages, stream, actionsEnabled, userId, ragSourceId, connectionId,
                toolFilter, toolChoice, null, null, null);
    }

    /** 기존 호환용 생성자 (connectionGroupId/workingDirectory 없음) */
    public ChatRequest(String model, String sessionId, List<UnifiedMessage> messages,
                       boolean stream, boolean actionsEnabled, String userId,
                       String ragSourceId, String connectionId,
                       ToolFilterContext toolFilter, String toolChoice,
                       ResponseFormat responseFormat) {
        this(model, sessionId, messages, stream, actionsEnabled, userId, ragSourceId, connectionId,
                toolFilter, toolChoice, responseFormat, null, null);
    }

    /** 기존 호환용 생성자 (workingDirectory 없음, CR-045 이전) */
    public ChatRequest(String model, String sessionId, List<UnifiedMessage> messages,
                       boolean stream, boolean actionsEnabled, String userId,
                       String ragSourceId, String connectionId,
                       ToolFilterContext toolFilter, String toolChoice,
                       ResponseFormat responseFormat, String connectionGroupId) {
        this(model, sessionId, messages, stream, actionsEnabled, userId, ragSourceId, connectionId,
                toolFilter, toolChoice, responseFormat, connectionGroupId, null);
    }

    /** CR-147: 단계에 지정한 스킬과 판을 실은 사본. */
    public ChatRequest withSkillSet(List<SkillRef> skillSet) {
        return new ChatRequest(model, sessionId, messages, stream, actionsEnabled, userId, ragSourceId, connectionId,
                toolFilter, toolChoice, responseFormat, connectionGroupId, workingDirectory,
                workflowRunId, workflowStepId, subagentRunId, toolModeOverride, systemPromptOverride, skillSet);
    }

    /** CR-161: 그룹이 고른 멤버로 단일 커넥션 요청을 만든다. 그룹은 지워 안에서 다시 고르지 않게 한다. */
    public ChatRequest withConnection(String memberConnectionId) {
        return new ChatRequest(model, sessionId, messages, stream, actionsEnabled, userId, ragSourceId, memberConnectionId,
                toolFilter, toolChoice, responseFormat, null, workingDirectory,
                workflowRunId, workflowStepId, subagentRunId, toolModeOverride, systemPromptOverride, skillSet);
    }

    public boolean hasConnectionGroup() {
        return connectionGroupId != null && !connectionGroupId.isBlank();
    }

    /**
     * 구조화된 출력 포맷 (CR-007).
     * @param type       "json_schema"
     * @param schemaRef  등록된 스키마 ID (e.g., "product-extract/1"). schema와 상호 배타적.
     * @param schema     인라인 JSON Schema. schemaRef와 상호 배타적.
     */
    public record ResponseFormat(
            String type,
            String schemaRef,
            Map<String, Object> schema
    ) {}
}
