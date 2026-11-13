package com.platform.tool.builtin;

import com.platform.policy.AuditLogger;
import com.platform.tenant.TenantContext;
import com.platform.tool.ToolContext;
import com.platform.tool.ToolRegistry;
import com.platform.tool.ToolResult;
import com.platform.workflow.WorkflowEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CR-189: 서버 도구 통로에서는 도구가 요청 스레드에서 돈다. 트리거가 끝날 때 테넌트를 지우면
 * 같은 요청의 뒤 훅과 같은 턴의 다음 도구가 테넌트를 잃는다.
 */
class RemoteTriggerToolTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("트리거가 끝나도 부른 쪽 테넌트가 그대로 남는다")
    @SuppressWarnings("unchecked")
    void caller_tenant_is_restored_after_trigger() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.execute(any(), any())).thenReturn(ToolResult.ok(Map.of(), "done"));
        RemoteTriggerTool tool = new RemoteTriggerTool(mock(WorkflowEngine.class), toolRegistry,
                mock(AuditLogger.class), mock(RedisTemplate.class));
        TenantContext.setTenantId("workmap");

        ToolResult result = tool.execute(Map.of("target_type", "TOOL", "target_id", "cron_list"),
                ToolContext.minimal("workmap", "sess-1"));

        assertThat(result.success()).isTrue();
        assertThat(TenantContext.getTenantId()).isEqualTo("workmap");
    }
}
