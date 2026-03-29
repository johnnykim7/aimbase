package com.platform.session;

/**
 * 메모리 계층 (PRD-130).
 *
 * SYSTEM_RULES: 항상 주입 (토큰 10%)
 * LONG_TERM: 세션 간 유지, relevance 기반 top-K (토큰 15%)
 * SHORT_TERM: 현재 대화 (토큰 75%)
 * USER_PROFILE: 사용자 선호/이력
 */
public enum MemoryLayer {
    SYSTEM_RULES,
    LONG_TERM,
    SHORT_TERM,
    USER_PROFILE
}
