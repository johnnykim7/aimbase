package com.platform.agent;

import com.platform.domain.PlanStatus;

import java.util.Map;
import java.util.Set;

/**
 * CR-033 PRD-224: Plan Mode FSM 전이 검증.
 * BIZ-054: PLANNING→EXECUTING→VERIFYING→COMPLETED
 */
public final class PlanStateMachine {

    private static final Map<PlanStatus, Set<PlanStatus>> TRANSITIONS = Map.of(
            PlanStatus.PLANNING, Set.of(PlanStatus.EXECUTING, PlanStatus.ABANDONED),
            PlanStatus.EXECUTING, Set.of(PlanStatus.VERIFYING, PlanStatus.PLANNING),
            PlanStatus.VERIFYING, Set.of(PlanStatus.COMPLETED, PlanStatus.EXECUTING)
    );

    private PlanStateMachine() {}

    public static void validateTransition(PlanStatus from, PlanStatus to) {
        if (!TRANSITIONS.getOrDefault(from, Set.of()).contains(to)) {
            throw new IllegalStateException(
                    "Invalid plan transition: " + from + " → " + to);
        }
    }

    public static boolean canTransition(PlanStatus from, PlanStatus to) {
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }
}
