package com.platform.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.action.model.ActionMetadata;
import com.platform.action.model.ActionPayload;
import com.platform.action.model.ActionPolicy;
import com.platform.action.model.ActionRequest;
import com.platform.action.model.ActionTarget;
import com.platform.domain.PolicyEntity;
import com.platform.hook.HookDispatcher;
import com.platform.monitoring.PlatformMetrics;
import com.platform.policy.model.PolicyResult;
import com.platform.repository.PolicyRepository;
import com.platform.tool.PermissionLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * CR-030 PRD-196: PolicyEngine SpEL #permissionLevel 변수 노출 테스트.
 */
@ExtendWith(MockitoExtension.class)
class PolicyEnginePermissionLevelTest {

    @Mock private PolicyRepository policyRepository;
    @Mock private AuditLogger auditLogger;
    @Mock private PIIMasker piiMasker;
    @Mock private MCPSafetyClient mcpSafetyClient;
    @Mock private RateLimiter rateLimiter;
    @Mock private PlatformMetrics platformMetrics;
    @Mock private HookDispatcher hookDispatcher;

    private PolicyEngine engine;

    @BeforeEach
    void setUp() {
        engine = new PolicyEngine(
                policyRepository, auditLogger, new ObjectMapper(),
                piiMasker, mcpSafetyClient, rateLimiter, platformMetrics, hookDispatcher);
    }

    @Test
    void evaluate_withPermissionLevel_shouldExposeToSpEL_deny() {
        // DENY if #permissionLevel == 'FULL'
        PolicyEntity policy = buildPolicy(
                "#permissionLevel == 'FULL'", "DENY", "FULL permission blocked");
        when(policyRepository.findByIsActiveTrueOrderByPriorityDesc())
                .thenReturn(List.of(policy));

        PolicyResult result = engine.evaluate(buildRequest(), PermissionLevel.FULL);

        assertThat(result.action()).isEqualTo(PolicyResult.PolicyAction.DENY);
        assertThat(result.denialReason()).isEqualTo("FULL permission blocked");
    }

    @Test
    void evaluate_withPermissionLevel_shouldExposeToSpEL_allow() {
        // DENY if #permissionLevel == 'FULL' — but we pass READ_ONLY → condition false → skip → ALLOW
        PolicyEntity policy = buildPolicy(
                "#permissionLevel == 'FULL'", "DENY", "FULL permission blocked");
        when(policyRepository.findByIsActiveTrueOrderByPriorityDesc())
                .thenReturn(List.of(policy));

        PolicyResult result = engine.evaluate(buildRequest(), PermissionLevel.READ_ONLY);

        assertThat(result.action()).isEqualTo(PolicyResult.PolicyAction.ALLOW);
    }

    @Test
    void evaluate_withNullPermissionLevel_shouldNotBreakSpEL() {
        // condition references #permissionLevel but it's null → SpEL evaluates to false (null != 'FULL')
        PolicyEntity policy = buildPolicy(
                "#permissionLevel == 'FULL'", "DENY", "blocked");
        when(policyRepository.findByIsActiveTrueOrderByPriorityDesc())
                .thenReturn(List.of(policy));

        PolicyResult result = engine.evaluate(buildRequest(), null);

        assertThat(result.action()).isEqualTo(PolicyResult.PolicyAction.ALLOW);
    }

    @Test
    void evaluate_withoutPermissionLevel_legacyOverload_shouldWork() {
        // 1-arg evaluate → null permissionLevel → condition skipped → ALLOW
        PolicyEntity policy = buildPolicy(
                "#permissionLevel == 'READ_ONLY'", "DENY", "blocked");
        when(policyRepository.findByIsActiveTrueOrderByPriorityDesc())
                .thenReturn(List.of(policy));

        PolicyResult result = engine.evaluate(buildRequest());

        assertThat(result.action()).isEqualTo(PolicyResult.PolicyAction.ALLOW);
    }

    // ── 헬퍼 ──

    private ActionRequest buildRequest() {
        return new ActionRequest(
                UUID.randomUUID().toString(),
                "chat",
                ActionRequest.ActionType.WRITE,
                List.of(new ActionTarget("anthropic", "claude-sonnet-4-5", null)),
                new ActionPayload(null, Map.of(), null),
                new ActionPolicy(false, null, 3, 3000L, true),
                new ActionMetadata("session-1", "user-1", null)
        );
    }

    private PolicyEntity buildPolicy(String condition, String ruleType, String message) {
        PolicyEntity entity = new PolicyEntity();
        entity.setId("test-perm-policy");
        entity.setName("Permission Level Policy");
        entity.setPriority(10);
        entity.setActive(true);
        entity.setMatchRules(Map.of());
        entity.setRules(List.of(Map.of(
                "type", ruleType,
                "condition", condition,
                "message", message
        )));
        return entity;
    }
}
