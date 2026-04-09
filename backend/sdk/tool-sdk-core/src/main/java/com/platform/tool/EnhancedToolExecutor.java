package com.platform.tool;

import java.util.Map;

/**
 * CR-029: 확장된 도구 실행 인터페이스.
 *
 * 기존 ToolExecutor를 확장하여 ToolContext, ToolResult, ToolContractMeta,
 * ValidationResult를 지원한다.
 *
 * 기존 ToolExecutor.execute(Map) → String은 default bridge로 유지되어
 * 하위 호환성을 보장한다.
 */
public interface EnhancedToolExecutor extends ToolExecutor {

    /**
     * 도구의 계약 메타데이터를 반환.
     * 도구의 식별, 권한, 부작용, 동시성 안전성 등을 선언한다.
     */
    ToolContractMeta getContractMeta();

    /**
     * 컨텍스트 기반 도구 실행.
     *
     * @param input LLM이 전달한 인수 Map
     * @param ctx   실행 문맥 (테넌트, 세션, 권한, 워크스페이스 등)
     * @return 구조화된 실행 결과
     */
    ToolResult execute(Map<String, Object> input, ToolContext ctx);

    /**
     * 실행 전 입력 검증.
     * 기본 구현은 항상 통과.
     */
    default ValidationResult validateInput(Map<String, Object> input, ToolContext ctx) {
        return ValidationResult.OK;
    }

    /**
     * 기존 ToolExecutor.execute(Map) → String bridge.
     * EnhancedToolExecutor 구현체에서는 새 execute(Map, ToolContext)를 호출하고
     * summary를 반환한다.
     */
    @Override
    default String execute(Map<String, Object> input) {
        ToolResult result = execute(input, ToolContext.minimal(null, null));
        return result.summary();
    }
}
