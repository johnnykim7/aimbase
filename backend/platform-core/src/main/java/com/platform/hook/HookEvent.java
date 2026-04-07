package com.platform.hook;

/**
 * CR-030 PRD-189 + CR-034 PRD-231: 훅 이벤트 유형.
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
    POST_COMPACT,           // 압축 완료 후 알림

    // ── 서브에이전트 (PRD-207) ──
    SUBAGENT_START,         // 서브에이전트 실행 시작
    SUBAGENT_STOP,          // 서브에이전트 실행 완료/실패/타임아웃

    // ── CR-034 PRD-231: 알림/제어 ──
    NOTIFICATION,           // NotificationTool 실행 후 알림 발송
    STOP,                   // 세션 강제 중단 요청
    STOP_FAILURE,           // 세션 강제 중단 실패
    SETUP,                  // 최초 세션 설정(프로바이더, 모델 등) 완료

    // ── CR-034 PRD-231: 에이전트 협업 ──
    TEAMMATE_IDLE,          // 에이전트 대기 상태 전환 (유휴)
    TASK_CREATED,           // TaskCreateTool 실행 후 태스크 생성
    TASK_COMPLETED,         // Task 완료 상태 전환 (COMPLETED/FAILED)

    // ── CR-034 PRD-231: 사용자 상호작용 ──
    ELICITATION,            // 사용자 입력 요청 (추가 정보 필요)
    ELICITATION_RESULT,     // 사용자 입력 응답 수신

    // ── CR-034 PRD-231: 설정/환경 ──
    CONFIG_CHANGE,          // 런타임 설정 변경 (커넥션, 정책 등)
    WORKTREE_CREATE,        // Git Worktree 생성
    WORKTREE_REMOVE,        // Git Worktree 삭제
    INSTRUCTIONS_LOADED,    // 세션 시작 시 지시사항(시스템 프롬프트) 로드 완료
    MESSAGE_SENT            // SendMessageTool 에이전트 간 메시지 전송
}
