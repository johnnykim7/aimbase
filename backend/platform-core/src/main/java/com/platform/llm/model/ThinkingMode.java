package com.platform.llm.model;

/**
 * CR-031 PRD-214: Extended Thinking 3모드.
 *
 * <ul>
 *   <li>DISABLED — thinking 비활성</li>
 *   <li>ENABLED — 고정 budget (thinkingBudgetTokens 필수)</li>
 *   <li>ADAPTIVE — 모델이 자동으로 thinking budget 결정 (Claude 4.6+ 전용)</li>
 * </ul>
 */
public enum ThinkingMode {
    DISABLED,
    ENABLED,
    ADAPTIVE
}
