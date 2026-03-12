package com.platform.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.action.model.ActionMetadata;
import com.platform.action.model.ActionPayload;
import com.platform.action.model.ActionPolicy;
import com.platform.action.model.ActionRequest;
import com.platform.action.model.ActionTarget;
import com.platform.domain.PolicyEntity;
import com.platform.monitoring.PlatformMetrics;
import com.platform.policy.model.PolicyResult;
import com.platform.repository.PolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * PolicyEngine TRANSFORM 규칙 — MCPSafetyClient 연동 테스트.
 *
 * Sprint 16 (PY-009): Presidio MCP 우선, Java PIIMasker 폴백.
 */
@ExtendWith(MockitoExtension.class)
class PolicyEngineTransformTest {

    @Mock private PolicyRepository policyRepository;
    @Mock private AuditLogger auditLogger;
    @Mock private PIIMasker piiMasker;
    @Mock private MCPSafetyClient mcpSafetyClient;
    @Mock private RateLimiter rateLimiter;
    @Mock private PlatformMetrics platformMetrics;

    private PolicyEngine engine;

    @BeforeEach
    void setUp() {
        engine = new PolicyEngine(
                policyRepository, auditLogger, new ObjectMapper(),
                piiMasker, mcpSafetyClient, rateLimiter, platformMetrics);
    }

    private ActionRequest buildRequest(Map<String, Object> data) {
        return new ActionRequest(
                UUID.randomUUID().toString(),
                "chat",
                ActionRequest.ActionType.WRITE,
                List.of(new ActionTarget("anthropic", "claude-sonnet-4-5", null)),
                new ActionPayload(null, data, null),
                new ActionPolicy(false, null, 3, 3000L, true),
                new ActionMetadata("session-1", "user-1", null)
        );
    }

    private PolicyEntity buildTransformPolicy() {
        PolicyEntity entity = new PolicyEntity();
        entity.setId("pii-mask-policy");
        entity.setName("PII Masking");
        entity.setPriority(10);
        entity.setActive(true);
        entity.setMatchRules(Map.of());
        entity.setRules(List.of(Map.of("type", "TRANSFORM")));
        return entity;
    }

    @Test
    void transform_whenMcpAvailable_shouldUseMcpSafetyClient() {
        // given
        when(policyRepository.findByIsActiveTrueOrderByPriorityDesc())
                .thenReturn(List.of(buildTransformPolicy()));
        when(mcpSafetyClient.isAvailable()).thenReturn(true);
        when(mcpSafetyClient.maskPii(anyString()))
                .thenReturn(Map.of(
                        "masked_text", "전화: ***-****-****",
                        "pii_found", true,
                        "detections", List.of()));

        ActionRequest request = buildRequest(Map.of("message", "전화: 010-1234-5678"));

        // when
        PolicyResult result = engine.evaluate(request);

        // then
        assertThat(result.action()).isEqualTo(PolicyResult.PolicyAction.ALLOW);
        assertThat(result.transforms()).containsKey("masked_payload");

        @SuppressWarnings("unchecked")
        Map<String, Object> masked = (Map<String, Object>) result.transforms().get("masked_payload");
        assertThat(masked.get("message")).isEqualTo("전화: ***-****-****");

        verify(mcpSafetyClient).maskPii("전화: 010-1234-5678");
        verify(piiMasker, never()).maskMap(any());
    }

    @Test
    void transform_whenMcpUnavailable_shouldFallbackToPiiMasker() {
        // given
        when(policyRepository.findByIsActiveTrueOrderByPriorityDesc())
                .thenReturn(List.of(buildTransformPolicy()));
        when(mcpSafetyClient.isAvailable()).thenReturn(false);

        Map<String, Object> originalData = Map.of("message", "전화: 010-1234-5678");
        Map<String, Object> maskedData = Map.of("message", "전화: 010-****-****");
        when(piiMasker.maskMap(originalData)).thenReturn(maskedData);

        ActionRequest request = buildRequest(originalData);

        // when
        PolicyResult result = engine.evaluate(request);

        // then
        assertThat(result.action()).isEqualTo(PolicyResult.PolicyAction.ALLOW);
        assertThat(result.transforms()).containsKey("masked_payload");

        verify(piiMasker).maskMap(originalData);
        verify(mcpSafetyClient, never()).maskPii(anyString());
    }

    @Test
    void transform_whenMcpThrowsException_shouldFallbackToPiiMasker() {
        // given
        when(policyRepository.findByIsActiveTrueOrderByPriorityDesc())
                .thenReturn(List.of(buildTransformPolicy()));
        when(mcpSafetyClient.isAvailable()).thenReturn(true);
        when(mcpSafetyClient.maskPii(anyString()))
                .thenThrow(new RuntimeException("MCP connection lost"));

        Map<String, Object> originalData = Map.of("message", "주민번호: 901234-1234567");
        Map<String, Object> maskedData = Map.of("message", "주민번호: ******-*******");
        when(piiMasker.maskMap(originalData)).thenReturn(maskedData);

        ActionRequest request = buildRequest(originalData);

        // when
        PolicyResult result = engine.evaluate(request);

        // then
        assertThat(result.action()).isEqualTo(PolicyResult.PolicyAction.ALLOW);
        assertThat(result.transforms()).containsKey("masked_payload");

        // MCP 실패 후 PIIMasker 폴백
        verify(mcpSafetyClient).maskPii("주민번호: 901234-1234567");
        verify(piiMasker).maskMap(originalData);
    }
}
