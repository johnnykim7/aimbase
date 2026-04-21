package com.platform.hook;

/**
 * CR-030 PRD-189 + CR-034 PRD-231 + CR-047 PRD-301: 훅 이벤트 유형.
 *
 * 각 이벤트는 플랫폼 실행 흐름의 특정 시점에서 발생하며,
 * HookDispatcher가 등록된 훅을 매칭하여 실행한다.
 *
 * CR-047 PRD-301: synchronous 속성으로 동기/비동기 분류.
 * - synchronous=true (게이팅): 결정권 있음 — 결과를 메인 플로우가 사용 (BLOCK/MODIFY).
 * - synchronous=false (로그성): 결정권 없음 — fire-and-forget 비동기 실행 (감사·알림 등).
 */
public enum HookEvent {

    // ── Tool 실행 (PRD-193) ──
    PRE_TOOL_USE(true),            // 도구 호출 직전 (BLOCK 시 실행 스킵) — 게이팅
    POST_TOOL_USE(false),          // 도구 호출 성공 후 (감사·관측) — 로그성
    POST_TOOL_USE_FAILURE(false),  // 도구 호출 실패 시 (감사·관측) — 로그성

    // ── 오케스트레이션 (PRD-194) ──
    USER_PROMPT_SUBMIT(true),      // 사용자 프롬프트 진입 (프롬프트 수정 가능) — 게이팅
    SESSION_START(false),          // 세션 최초 생성 — 로그성
    SESSION_END(false),            // 응답 완료 후 — 로그성
    PERMISSION_REQUEST(true),      // PolicyEngine REQUIRE_APPROVAL 시 — 게이팅
    PERMISSION_DENIED(false),      // PolicyEngine DENY 시 — 로그성 (이미 거부됨)

    // ── 컨텍스트 압축 (PRD-195) ──
    PRE_COMPACT(true),             // 압축 전 (대상 메시지 변경 가능) — 게이팅
    POST_COMPACT(false),           // 압축 완료 후 알림 — 로그성

    // ── 서브에이전트 (PRD-207) ──
    SUBAGENT_START(false),         // 서브에이전트 실행 시작 — 로그성
    SUBAGENT_STOP(false),          // 서브에이전트 실행 완료/실패/타임아웃 — 로그성

    // ── CR-034 PRD-231: 알림/제어 ──
    NOTIFICATION(false),           // NotificationTool 실행 후 알림 발송 — 로그성
    STOP(true),                    // 세션 강제 중단 요청 — 게이팅
    STOP_FAILURE(false),           // 세션 강제 중단 실패 — 로그성
    SETUP(false),                  // 최초 세션 설정 완료 — 로그성

    // ── CR-034 PRD-231: 에이전트 협업 ──
    TEAMMATE_IDLE(false),          // 에이전트 대기 상태 전환 — 로그성
    TASK_CREATED(false),           // TaskCreateTool 실행 후 태스크 생성 — 로그성
    TASK_COMPLETED(false),         // Task 완료 상태 전환 — 로그성

    // ── CR-034 PRD-231: 사용자 상호작용 ──
    ELICITATION(true),             // 사용자 입력 요청 — 게이팅 (응답 대기 필요)
    ELICITATION_RESULT(false),     // 사용자 입력 응답 수신 — 로그성

    // ── CR-034 PRD-231: 설정/환경 ──
    CONFIG_CHANGE(false),          // 런타임 설정 변경 — 로그성
    WORKTREE_CREATE(false),        // Git Worktree 생성 — 로그성
    WORKTREE_REMOVE(false),        // Git Worktree 삭제 — 로그성
    INSTRUCTIONS_LOADED(false),    // 시스템 프롬프트 로드 완료 — 로그성
    MESSAGE_SENT(false);           // SendMessageTool 에이전트 간 메시지 전송 — 로그성

    private final boolean synchronous;

    HookEvent(boolean synchronous) {
        this.synchronous = synchronous;
    }

    /** CR-047 PRD-301: true면 동기 실행(결정권 있음), false면 비동기 실행(로그성). */
    public boolean isSynchronous() {
        return synchronous;
    }
}
