package com.platform.orchestrator;

import com.platform.llm.model.UnifiedMessage;
import com.platform.tool.ToolFilterContext;

import java.util.List;
import java.util.Map;

/**
 * @param toolFilter          도구 필터링 컨텍스트 (null이면 전체 도구 노출)
 * @param toolChoice          도구 선택 전략 (null/"auto": 자율, "none": 미사용, "required": 필수, "{tool_name}": 강제)
 * @param responseFormat      구조화된 출력 요청 (CR-007). null이면 일반 텍스트 응답.
 * @param connectionGroupId   커넥션 그룹 ID (CR-015). 그룹 내 전략에 따라 커넥션 선택 + 자동 폴백.
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
        String connectionGroupId
) {
    public ChatRequest(String model, List<UnifiedMessage> messages) {
        this(model, null, messages, false, false, null, null, null, null, null, null, null);
    }

    /** 기존 호환용 생성자 (toolFilter/toolChoice/responseFormat/connectionGroupId 없음) */
    public ChatRequest(String model, String sessionId, List<UnifiedMessage> messages,
                       boolean stream, boolean actionsEnabled, String userId,
                       String ragSourceId, String connectionId) {
        this(model, sessionId, messages, stream, actionsEnabled, userId, ragSourceId, connectionId, null, null, null, null);
    }

    /** 기존 호환용 생성자 (responseFormat/connectionGroupId 없음) */
    public ChatRequest(String model, String sessionId, List<UnifiedMessage> messages,
                       boolean stream, boolean actionsEnabled, String userId,
                       String ragSourceId, String connectionId,
                       ToolFilterContext toolFilter, String toolChoice) {
        this(model, sessionId, messages, stream, actionsEnabled, userId, ragSourceId, connectionId,
                toolFilter, toolChoice, null, null);
    }

    /** 기존 호환용 생성자 (connectionGroupId 없음) */
    public ChatRequest(String model, String sessionId, List<UnifiedMessage> messages,
                       boolean stream, boolean actionsEnabled, String userId,
                       String ragSourceId, String connectionId,
                       ToolFilterContext toolFilter, String toolChoice,
                       ResponseFormat responseFormat) {
        this(model, sessionId, messages, stream, actionsEnabled, userId, ragSourceId, connectionId,
                toolFilter, toolChoice, responseFormat, null);
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
