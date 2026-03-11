package com.platform.tool;

import com.platform.llm.model.UnifiedToolDef;

import java.util.Map;

/**
 * 단일 도구 실행 단위.
 * BuiltIn 도구 및 MCP 연동 도구 모두 이 인터페이스를 구현.
 */
public interface ToolExecutor {

    /** LLM에 전달할 도구 정의 (이름, 설명, 입력 스키마) */
    UnifiedToolDef getDefinition();

    /**
     * 도구 실행.
     * @param input LLM이 전달한 인수 Map
     * @return 실행 결과 문자열 (LLM의 tool_result content로 전달됨)
     */
    String execute(Map<String, Object> input);
}
