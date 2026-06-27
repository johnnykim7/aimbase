package com.platform.largeinput;

/**
 * CR-120: 대용량 입력 분해 정책.
 *
 * <ul>
 *   <li>{@link #OFF} — 분해 안 함. 입력을 청크 1개로 통째 처리(기존 동작과 동일, 32MB 위험 감수).</li>
 *   <li>{@link #AUTO} — 사전 크기측정 후 임계 초과면 분해. LLM_CALL 백엔드만 우리가 크기를 100% 통제하므로
 *       사전측정 가능. AGENT_CALL/CLI 는 자율주행이 크기를 통제 못 하므로 AUTO 를 FORCE 로 정규화한다.</li>
 *   <li>{@link #FORCE} — 항상 분해.</li>
 *   <li>{@link #FORBID} — 분해 금지 + 큰 입력이면 즉시 실패(분해 없이 32MB 칠 위험을 차단).</li>
 * </ul>
 *
 * 우선순위: STEP config > 워크플로우 > 시스템 기본(AUTO).
 */
public enum LargeInputPolicy {
    OFF, AUTO, FORCE, FORBID;

    public static LargeInputPolicy fromString(String s, LargeInputPolicy def) {
        if (s == null || s.isBlank()) return def;
        try {
            return valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return def;
        }
    }

    /** STEP > WF > SYSTEM 우선순위 해석. 각 인자는 null 허용. */
    public static LargeInputPolicy resolve(String step, String workflow, String system) {
        LargeInputPolicy sys = fromString(system, AUTO);
        LargeInputPolicy wf = fromString(workflow, sys);
        return fromString(step, wf);
    }
}
