package com.platform.tool;

import java.util.List;

/**
 * CR-006: 도구 선택 제어 컨텍스트.
 *
 * tool_choice 모드 (ChatRequest.toolChoice로 전달):
 * - "auto" (기본): LLM이 자유롭게 도구 선택
 * - "none": 도구 사용 비활성화
 * - "required": 반드시 도구를 사용해야 함
 * - "{tool_name}": 특정 도구만 강제 사용
 *
 * allowedTools/excludeTools로 세밀한 필터링 가능.
 * 필터 적용 순서: allowedTools → excludeTools
 */
public record ToolFilterContext(
        List<String> allowedTools,
        List<String> excludeTools,
        List<String> tags,
        // --- CR-029 신규 ---
        List<String> requiredCapabilities,
        PermissionLevel maxPermission,
        Boolean readOnlyMode,
        // CR-183: 노출 목록과 세션 활성 목록에 없어도 이 요청에 붙이는 도구(BIZ-222). exclude 에 있으면 붙이지 않는다.
        List<String> attachedTools
) {
    /** CR-029 6칸 호환 (CR-183 attachedTools 없음) */
    public ToolFilterContext(List<String> allowedTools, List<String> excludeTools, List<String> tags,
                             List<String> requiredCapabilities, PermissionLevel maxPermission,
                             Boolean readOnlyMode) {
        this(allowedTools, excludeTools, tags, requiredCapabilities, maxPermission, readOnlyMode, null);
    }

    /** CR-006 하위호환: 3-arg constructor */
    public ToolFilterContext(List<String> allowedTools, List<String> excludeTools, List<String> tags) {
        this(allowedTools, excludeTools, tags, null, null, null);
    }

    /** 필터 없음 (전체 도구 노출) */
    /** CR-183: 붙이는 도구를 더한 복사본. null 필터에서도 부를 수 있게 정적 메서드다. */
    public static ToolFilterContext withAttached(ToolFilterContext base, List<String> attached) {
        if (base == null) return new ToolFilterContext(null, null, null, null, null, null, attached);
        return new ToolFilterContext(base.allowedTools, base.excludeTools, base.tags, base.requiredCapabilities,
                base.maxPermission, base.readOnlyMode, attached);
    }

    /** CR-183: 이 요청에 붙이는 도구인지. allowedTools 와 상관없이 붙이고 excludeTools 에 있으면 붙이지 않는다. */
    public boolean attaches(String toolName) {
        if (attachedTools == null || !attachedTools.contains(toolName)) return false;
        return excludeTools == null || !excludeTools.contains(toolName);
    }

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

    /**
     * 주어진 도구 이름이 이 필터 컨텍스트에서 허용되는지 확인.
     *
     * @param toolName 검사할 도구 이름
     * @return 허용이면 true
     */
    public boolean isToolAllowed(String toolName) {
        // allowedTools가 지정되면 해당 목록에 포함된 도구만 허용
        if (allowedTools != null && !allowedTools.isEmpty()) {
            if (!allowedTools.contains(toolName)) {
                return false;
            }
        }
        // excludeTools가 지정되면 해당 목록에 포함된 도구는 제외
        if (excludeTools != null && !excludeTools.isEmpty()) {
            if (excludeTools.contains(toolName)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 사용 가능한 도구 이름 목록을 이 필터 컨텍스트에 따라 필터링.
     *
     * @param availableTools 전체 도구 이름 목록
     * @return 필터링된 도구 이름 목록
     */
    public List<String> filterTools(List<String> availableTools) {
        return availableTools.stream()
                .filter(this::isToolAllowed)
                .toList();
    }
}
