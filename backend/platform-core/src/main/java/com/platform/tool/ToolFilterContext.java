package com.platform.tool;

import java.util.List;

/**
 * LLM에 노출할 도구를 필터링하기 위한 컨텍스트.
 *
 * - allowedTools: 이 목록에 포함된 도구만 노출 (null이면 전체 허용)
 * - excludeTools: 이 목록에 포함된 도구는 제외 (null이면 제외 없음)
 * - tags: 향후 태그 기반 필터링 확장용 (현재 미사용)
 *
 * 필터 적용 순서: allowedTools → excludeTools
 */
public record ToolFilterContext(
        List<String> allowedTools,
        List<String> excludeTools,
        List<String> tags
) {
    /** 필터 없음 (전체 도구 노출) */
    public static ToolFilterContext none() {
        return new ToolFilterContext(null, null, null);
    }

    /** 허용 목록만 지정 */
    public static ToolFilterContext allowOnly(List<String> allowedTools) {
        return new ToolFilterContext(allowedTools, null, null);
    }

    /** 제외 목록만 지정 */
    public static ToolFilterContext exclude(List<String> excludeTools) {
        return new ToolFilterContext(null, excludeTools, null);
    }
}
