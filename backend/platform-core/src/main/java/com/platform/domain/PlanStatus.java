package com.platform.domain;

/**
 * CR-033 PRD-222~224: Plan Mode 상태.
 * BIZ-054: FSM 전이 규칙 — PLANNING→EXECUTING→VERIFYING→COMPLETED
 */
public enum PlanStatus {
    PLANNING,
    EXECUTING,
    VERIFYING,
    COMPLETED,
    ABANDONED
}
