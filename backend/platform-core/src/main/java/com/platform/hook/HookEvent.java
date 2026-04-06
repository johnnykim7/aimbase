package com.platform.hook;

/**
 * CR-030 PRD-189: 훅 이벤트 유형.
 *
 * 각 이벤트는 플랫폼 실행 흐름의 특정 시점에서 발생하며,
 * HookDispatcher가 등록된 훅을 매칭하여 실행한다.
 */
public enum HookEvent {

    // ── Tool 실행 (PRD-193) ──
    PRE_TOOL_USE,           // 도구 호출 직전 (BLOCK 시 실행 스킵)
    POST_TOOL_USE,          // 도구 호출 성공 후 (result 수정 가능)
    POST_TOOL_USE_FAILURE,  // 도구 호출 실패 시

    // ── 오케스트레이션 (PRD-194) ──
    USER_PROMPT_SUBMIT,     // 사용자 프롬프트 진입 (프롬프트 수정 가능)
    SESSION_START,          // 세션 최초 생성
    SESSION_END,            // 응답 완료 후
    PERMISSION_REQUEST,     // PolicyEngine REQUIRE_APPROVAL 시
    PERMISSION_DENIED,      // PolicyEngine DENY 시

    // ── 컨텍스트 압축 (PRD-195) ──
    PRE_COMPACT,            // 압축 전 (대상 메시지 변경 가능)
    POST_COMPACT            // 압축 완료 후 알림
}
