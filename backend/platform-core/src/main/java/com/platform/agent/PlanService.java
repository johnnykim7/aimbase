package com.platform.agent;

import com.platform.domain.PlanEntity;
import com.platform.domain.PlanStatus;
import com.platform.repository.PlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * CR-033 PRD-222~224: Plan Mode 비즈니스 로직.
 */
@Component
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);
    private static final List<PlanStatus> ACTIVE_STATUSES =
            List.of(PlanStatus.PLANNING, PlanStatus.EXECUTING, PlanStatus.VERIFYING);

    private final PlanRepository planRepository;

    public PlanService(PlanRepository planRepository) {
        this.planRepository = planRepository;
    }

    /** BIZ-053: 세션에 활성 Plan이 있는지 확인 */
    public boolean hasActivePlan(String sessionId) {
        return planRepository.countBySessionIdAndStatusIn(sessionId, ACTIVE_STATUSES) > 0;
    }

    /** 새 Plan 생성 (PLANNING 상태) */
    public PlanEntity createPlan(String sessionId, String title, List<String> goals, List<String> constraints) {
        PlanEntity plan = new PlanEntity();
        plan.setSessionId(sessionId);
        plan.setTitle(title);
        plan.setGoals(goals);
        plan.setSteps(List.of());
        plan.setPlanConstraints(constraints);
        plan.setStatus(PlanStatus.PLANNING);
        return planRepository.save(plan);
    }

    /** Plan 조회 */
    public Optional<PlanEntity> getPlan(UUID planId) {
        return planRepository.findById(planId);
    }

    /** 세션의 활성 Plan 조회 */
    public Optional<PlanEntity> getActivePlan(String sessionId) {
        return planRepository.findBySessionIdAndStatusIn(sessionId, ACTIVE_STATUSES);
    }

    /** 세션의 최신 Plan 조회 */
    public Optional<PlanEntity> getLatestPlan(String sessionId) {
        return planRepository.findTopBySessionIdOrderByCreatedAtDesc(sessionId);
    }

    /** 세션의 전체 Plan 목록 */
    public List<PlanEntity> getPlans(String sessionId) {
        return planRepository.findBySessionIdOrderByCreatedAtDesc(sessionId);
    }

    /** Plan Mode 종료 — PLANNING→EXECUTING 전이 */
    public PlanEntity exitPlanMode(UUID planId, List<Map<String, Object>> steps, String summary) {
        PlanEntity plan = planRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Plan not found: " + planId));

        PlanStateMachine.validateTransition(plan.getStatus(), PlanStatus.EXECUTING);
        plan.setSteps(steps);
        plan.setStatus(PlanStatus.EXECUTING);
        if (summary != null) {
            plan.setVerificationResult(Map.of("summary", summary));
        }
        return planRepository.save(plan);
    }

    /** 계획 검증 — EXECUTING→VERIFYING→COMPLETED 전이 */
    public PlanEntity verify(UUID planId, List<Map<String, Object>> stepResults) {
        PlanEntity plan = planRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Plan not found: " + planId));

        PlanStateMachine.validateTransition(plan.getStatus(), PlanStatus.VERIFYING);
        plan.setStatus(PlanStatus.VERIFYING);

        // step별 결과 매칭 + 완료율 산출
        List<Map<String, Object>> steps = plan.getSteps();
        int totalSteps = steps.size();
        int verifiedSteps = 0;
        List<Map<String, Object>> gaps = new java.util.ArrayList<>();

        if (stepResults != null) {
            for (Map<String, Object> sr : stepResults) {
                String stepId = (String) sr.get("step_id");
                String resultStatus = (String) sr.get("status");
                if ("done".equals(resultStatus)) {
                    verifiedSteps++;
                } else {
                    gaps.add(Map.of("step_id", stepId, "issue", resultStatus));
                }
            }
        }

        double completionRate = totalSteps > 0 ? (double) verifiedSteps / totalSteps * 100 : 0;

        Map<String, Object> result = Map.of(
                "completion_rate", completionRate,
                "verified_steps", verifiedSteps,
                "total_steps", totalSteps,
                "gaps", gaps
        );
        plan.setVerificationResult(result);

        // 완료율 100%이면 COMPLETED
        if (completionRate >= 100.0) {
            plan.setStatus(PlanStatus.COMPLETED);
            log.info("Plan {} completed with 100% verification", planId);
        } else {
            // gaps 있으면 EXECUTING으로 복귀
            plan.setStatus(PlanStatus.EXECUTING);
            log.info("Plan {} has gaps, reverting to EXECUTING ({}%)", planId, completionRate);
        }

        return planRepository.save(plan);
    }

    /** 계획 포기 — PLANNING/EXECUTING→ABANDONED */
    public PlanEntity abandon(UUID planId) {
        PlanEntity plan = planRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Plan not found: " + planId));

        PlanStateMachine.validateTransition(plan.getStatus(), PlanStatus.ABANDONED);
        plan.setStatus(PlanStatus.ABANDONED);
        return planRepository.save(plan);
    }
}
