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
import com.platform.policy.model.PolicyResult.PolicyAction;
import com.platform.repository.PolicyRepository;
import com.platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** CR-185 TC-CHT-110: 도구 대상 정책 평가. */
class PolicyEngineToolCallTest {

    private PolicyRepository repo;
    private PolicyEngine engine;

    @BeforeEach
    void setUp() {
        repo = mock(PolicyRepository.class);
        engine = new PolicyEngine(repo, mock(AuditLogger.class), new ObjectMapper(), mock(PIIMasker.class),
                mock(MCPSafetyClient.class), mock(RateLimiter.class), mock(PlatformMetrics.class),
                mock(HookDispatcher.class));
        TenantContext.setTenantId("t1");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void 테넌트_없는_실행은_정책을_조회하지_않고_허용() {
        TenantContext.clear();
        PolicyEngine.ToolCallDecision d = engine.evaluateToolCall("send_mail", "s");
        assertThat(d.action()).isEqualTo(PolicyAction.ALLOW);
        assertThat(engine.hasToolPolicy("send_mail")).isFalse();
        verifyNoInteractions(repo);
    }

    private static PolicyEntity policy(String id, int priority, List<String> tools, String type, String message) {
        PolicyEntity p = new PolicyEntity();
        p.setId(id);
        p.setName(id);
        p.setPriority(priority);
        p.setActive(true);
        p.setMatchRules(Map.of(PolicyEngine.MATCH_TOOLS, tools));
        p.setRules(List.of(message != null ? Map.of("type", type, "message", message) : Map.of("type", type)));
        return p;
    }

    @Test
    void 대상_도구는_승인_필요와_안내_문구() {
        when(repo.findByIsActiveTrueOrderByPriorityDesc())
                .thenReturn(List.of(policy("p1", 10, List.of("send_mail"), "REQUIRE_APPROVAL", "발송 전 확인")));
        PolicyEngine.ToolCallDecision d = engine.evaluateToolCall("send_mail", "s");
        assertThat(d.action()).isEqualTo(PolicyAction.REQUIRE_APPROVAL);
        assertThat(d.policyId()).isEqualTo("p1");
        assertThat(d.message()).isEqualTo("발송 전 확인");
    }

    @Test
    void 대상_밖_도구와_정책_없는_승인_필요_표시_도구는_허용() {
        when(repo.findByIsActiveTrueOrderByPriorityDesc())
                .thenReturn(List.of(policy("p1", 10, List.of("send_mail"), "REQUIRE_APPROVAL", null)));
        assertThat(engine.evaluateToolCall("read_file", "s").action()).isEqualTo(PolicyAction.ALLOW);
        assertThat(engine.evaluateToolCall("http_request", "s").action()).isEqualTo(PolicyAction.ALLOW);
        assertThat(engine.hasToolPolicy("http_request")).isFalse();
        assertThat(engine.hasToolPolicy("send_mail")).isTrue();
    }

    @Test
    void 같은_도구에_높은_순위_거부가_먼저다() {
        when(repo.findByIsActiveTrueOrderByPriorityDesc()).thenReturn(List.of(
                policy("deny", 20, List.of("send_mail"), "DENY", null),
                policy("ask", 10, List.of("send_mail"), "REQUIRE_APPROVAL", null)));
        PolicyEngine.ToolCallDecision d = engine.evaluateToolCall("send_mail", "s");
        assertThat(d.action()).isEqualTo(PolicyAction.DENY);
        assertThat(d.message()).isNull();
    }

    @Test
    void 작업_평가는_도구_대상_정책을_건너뛴다() {
        when(repo.findByIsActiveTrueOrderByPriorityDesc())
                .thenReturn(List.of(policy("deny", 20, List.of("send_mail"), "DENY", null)));
        ActionRequest request = new ActionRequest(UUID.randomUUID().toString(), "chat",
                ActionRequest.ActionType.WRITE,
                List.of(new ActionTarget("anthropic", "claude-sonnet-4-5", null)),
                new ActionPayload(null, Map.of(), null),
                new ActionPolicy(false, null, 3, 3000L, true),
                new ActionMetadata("session-1", "user-1", null));
        assertThat(engine.evaluate(request).action()).isEqualTo(PolicyAction.ALLOW);
    }
}
