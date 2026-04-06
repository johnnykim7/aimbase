package com.platform.hook;

import java.util.Map;

/**
 * CR-030 PRD-189: 훅에 전달되는 입력 데이터.
 *
 * @param sessionId  현재 세션 ID (없으면 null)
 * @param event      발생 이벤트
 * @param toolName   도구명 (Tool 이벤트가 아니면 null)
 * @param input      이벤트별 입력 데이터 (도구 입력, 프롬프트 텍스트 등)
 * @param context    추가 컨텍스트 (세션 메타, 정책 결과 등)
 */
public record HookInput(
        String sessionId,
        HookEvent event,
        String toolName,
        Map<String, Object> input,
        Map<String, Object> context
) {
    public static HookInput of(HookEvent event, String sessionId) {
        return new HookInput(sessionId, event, null, Map.of(), Map.of());
    }

    public static HookInput of(HookEvent event, String sessionId, String toolName, Map<String, Object> input) {
        return new HookInput(sessionId, event, toolName, input != null ? input : Map.of(), Map.of());
    }

    public static HookInput of(HookEvent event, String sessionId, Map<String, Object> input, Map<String, Object> context) {
        return new HookInput(sessionId, event, null, input != null ? input : Map.of(), context != null ? context : Map.of());
    }
}
