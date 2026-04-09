package com.platform.runtime;

/**
 * CR-029 (PRD-183): 런타임 어댑터 인터페이스.
 * ClaudeTool, LLM API, MCP-only 등 하위 실행기를 추상화한다.
 */
public interface RuntimeAdapter {

    /** 런타임 식별자 (e.g., "claude_tool", "llm_api", "mcp_only") */
    String getRuntimeId();

    /** 런타임 능력 프로필 */
    RuntimeCapabilityProfile getCapabilities();

    /** 실행 */
    RuntimeResult execute(RuntimeRequest request);

    /** 런타임별 출력을 공통 포맷으로 정규화 */
    default String normalizeOutput(RuntimeResult result) {
        return result.response() != null ? result.response().textContent() : "";
    }
}
