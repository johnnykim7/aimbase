package com.platform.tool;

/**
 * CR-029: 도구 실행 권한 수준.
 * CR-030 PRD-196: AUTO 추가 — PermissionClassifier가 요청 컨텍스트 기반으로 자동 분류.
 */
public enum PermissionLevel {
    READ_ONLY,
    RESTRICTED_WRITE,
    FULL,

    /**
     * PRD-196: 자동 권한 분류 모드.
     * PermissionClassifier가 요청 내용 + 도구 유형 기반으로
     * READ_ONLY / RESTRICTED_WRITE / FULL 중 하나로 해소.
     * ordinal 비교 대상이 아님 — 반드시 resolve 후 사용.
     */
    AUTO
}
