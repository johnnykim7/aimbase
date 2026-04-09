package com.platform.session;

/**
 * 메모리 범위 (PRD-199).
 *
 * PRIVATE: sessionId/userId 기반 개인 메모리 (기존 동작)
 * TEAM:    tenantId + teamId 기반 팀 공유 메모리
 * GLOBAL:  master DB, 플랫폼 관리자 전용
 */
public enum MemoryScope {
    PRIVATE,
    TEAM,
    GLOBAL
}
