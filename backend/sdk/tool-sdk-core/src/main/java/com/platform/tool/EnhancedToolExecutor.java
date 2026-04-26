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
     *
     * <p>CR-067: 이전에는 {@link ToolResult#summary()} 만 반환해 output 본문(파일 내용·stdout·grep 결과 등)이
     * MCP/Controller/Cron/RemoteTrigger 모든 경로에서 손실됐다. 본 bridge 는 {@link ToolResultRenderer}
     * 를 통해 본문을 보존하며 직렬화한다.
     *
     * <p>호출처(MCP 등)가 {@link ToolContext} 를 알 수 없으므로 {@link ToolContext#minimal} 로 합성한다.
     * 호출처가 컨텍스트를 가진 경우에는 신 {@link #execute(Map, ToolContext)} 를 직접 호출해야 한다.
     */
    @Override
    default String execute(Map<String, Object> input) {
        ToolResult result = execute(input, ToolContext.minimal(null, null));
        return ToolResultRenderer.render(result);
    }
}
