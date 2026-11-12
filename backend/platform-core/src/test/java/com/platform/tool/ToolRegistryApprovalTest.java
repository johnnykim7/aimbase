package com.platform.tool;

import com.platform.llm.model.ToolCall;
import com.platform.mcp.server.McpExposurePolicy;
import com.platform.monitoring.PlatformMetrics;
import com.platform.orchestrator.approval.ToolApprovalGate;
import com.platform.tool.model.UnifiedToolDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CR-185 BIZ-241 TC-CHT-117: 도구를 실행하는 모든 길(대화, 워크플로우 단계, 예약, 관리 화면, 다른 도구의 대신 호출)이
 * 지나는 공통 지점에서 실행 전 승인을 묻는다.
 */
class ToolRegistryApprovalTest {

    private static final String REFUSAL = "승인이 필요한 도구라 위젯 대화에서만 실행할 수 있습니다";

    private ToolApprovalGate gate;
    private AtomicInteger runs;
    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        gate = mock(ToolApprovalGate.class);
        runs = new AtomicInteger();
        UnifiedToolDef def = new UnifiedToolDef("refund_order", "환불", Map.of("type", "object"));
        ToolExecutor tool = new ToolExecutor() {
            @Override
            public UnifiedToolDef getDefinition() { return def; }

            @Override
            public String execute(Map<String, Object> args) {
                runs.incrementAndGet();
                return "{\"ok\":true}";
            }
        };
        registry = new ToolRegistry(List.of(), mock(PlatformMetrics.class), mock(McpExposurePolicy.class), gate);
        registry.register(tool);
    }

    @Test
    void 문맥_실행은_도구_문맥의_세션으로_묻고_막히면_실행하지_않는다() {
        when(gate.check(eq("refund_order"), any(), eq("toolu_1"), eq("sess-1")))
                .thenReturn(ToolApprovalGate.Verdict.refuse("승인 시간이 지나 실행하지 않았습니다"));

        ToolResult r = registry.execute(new ToolCall("toolu_1", "refund_order", Map.of("order_id", "A-1")),
                ToolContext.minimal("t1", "sess-1"));

        assertThat(r.success()).isFalse();
        assertThat(r.summary()).isEqualTo("승인 시간이 지나 실행하지 않았습니다");
        assertThat(runs.get()).isZero();
    }

    @Test
    void 세션_없는_실행은_대화_밖으로_물어_막힌다() {
        when(gate.check(eq("refund_order"), any(), any(), isNull()))
                .thenReturn(ToolApprovalGate.Verdict.refuse(REFUSAL));

        String r = registry.execute(new ToolCall("c1", "refund_order", Map.of()));

        assertThat(r).isEqualTo(REFUSAL);
        assertThat(runs.get()).isZero();
    }

    @Test
    void 세션을_주면_그_세션으로_묻고_허용이면_실행한다() {
        when(gate.check(eq("refund_order"), any(), eq("c1"), eq("sess-2")))
                .thenReturn(ToolApprovalGate.Verdict.go());

        String r = registry.executeInSession(new ToolCall("c1", "refund_order", Map.of()), "sess-2");

        assertThat(r).isEqualTo("{\"ok\":true}");
        assertThat(runs.get()).isEqualTo(1);
    }
}
