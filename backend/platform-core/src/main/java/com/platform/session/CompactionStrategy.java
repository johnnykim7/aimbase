package com.platform.session;

/**
 * 압축 전략 (PRD-203, CR-030 Phase 5).
 *
 * 컨텍스트 윈도우 사용률에 따라 5단계 전략을 순차 적용한다.
 * <pre>
 * SNIP           → 오래된 메시지 제거 (LLM 호출 없음)
 * MICRO_COMPACT  → 최하위 1/3 → 200자 불릿 요약 (Haiku)
 * SESSION_MEMORY → 장기 기억으로 대화 대체 (LLM 호출 없음)
 * AUTO_COMPACT   → 전체 요약 → 500자 (Haiku)
 * BLOCK          → 차단 한계 도달, 요청 거부 (429)
 * </pre>
 */
public enum CompactionStrategy {
    SNIP,
    MICRO_COMPACT,
    SESSION_MEMORY,
    AUTO_COMPACT,
    BLOCK
}
