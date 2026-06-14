package com.platform.workflow.step;

import com.platform.llm.model.ToolCall;
import com.platform.tenant.TenantContext;
import com.platform.tool.ToolContext;
import com.platform.tool.ToolRegistry;
import com.platform.tool.ToolResult;
import com.platform.workflow.StepContext;
import com.platform.workflow.model.WorkflowStep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CR-107: TOOL_CALL 스텝이 run 의 workspace/세션 식별자를 ToolContext 로 전파해
 * 2-인자 execute(ToolCall, ToolContext) 경로를 타는지 검증.
 * 1-인자 경로(default bridge → ToolContext.minimal(null,null) → default/general 폴백) 회귀 방지.
 */
class ToolCallStepExecutorTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private ToolCallStepExecutor newExecutor(ToolRegistry registry) {
        return new ToolCallStepExecutor(registry, null); // eventRecorderProvider null 허용
    }

    private WorkflowStep toolStep(String toolName) {
        return new WorkflowStep("s1", "tool", WorkflowStep.StepType.TOOL_CALL,
                Map.of("tool", toolName, "input", Map.of("file_path", "out.bin")),
                null, null, null, null);
    }

    private StepContext ctxWithWorkspace(String workspacePath) {
        return new StepContext("run-123", "wf-1", "sess-9", Map.of(), new java.util.LinkedHashMap<>())
                .withWorkspacePath(workspacePath);
    }

    @Test
    void propagatesWorkspaceAndSessionIntoToolContext() {
        TenantContext.setTenantId("tenant-A");
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.execute(any(ToolCall.class), any(ToolContext.class)))
                .thenReturn(ToolResult.ok(Map.of("ok", true), "done"));

        ToolCallStepExecutor exec = newExecutor(registry);
        exec.execute(toolStep("download_file"), ctxWithWorkspace("/data/workspace/tenant-A/run-ws"));

        ArgumentCaptor<ToolContext> captor = ArgumentCaptor.forClass(ToolContext.class);
        org.mockito.Mockito.verify(registry).execute(any(ToolCall.class), captor.capture());
        ToolContext sent = captor.getValue();

        assertThat(sent.workspacePath()).isEqualTo("/data/workspace/tenant-A/run-ws"); // 폴백 아님
        assertThat(sent.tenantId()).isEqualTo("tenant-A");
        assertThat(sent.sessionId()).isEqualTo("sess-9");
        assertThat(sent.workflowRunId()).isEqualTo("run-123");
        assertThat(sent.stepId()).isEqualTo("s1");
    }

    @Test
    void nullWorkspace_passesNull_notDefaultGeneral() {
        // workspacePath 미지정 시 ToolContext.workspacePath=null 로 전달 — 폴백 결정은 WorkspaceResolver 몫.
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.execute(any(ToolCall.class), any(ToolContext.class)))
                .thenReturn(ToolResult.ok("x", "done"));

        newExecutor(registry).execute(toolStep("file_write"), ctxWithWorkspace(null));

        ArgumentCaptor<ToolContext> captor = ArgumentCaptor.forClass(ToolContext.class);
        org.mockito.Mockito.verify(registry).execute(any(ToolCall.class), captor.capture());
        assertThat(captor.getValue().workspacePath()).isNull();
    }

    @Test
    void failedToolResult_isPromotedToRuntimeException() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.execute(any(ToolCall.class), any(ToolContext.class)))
                .thenReturn(ToolResult.error("download failed: HTTP 404"));

        assertThatThrownBy(() ->
                newExecutor(registry).execute(toolStep("download_file"), ctxWithWorkspace("/ws")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("HTTP 404");
    }

    @Test
    void successResult_returnsRenderedOutput() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.execute(any(ToolCall.class), any(ToolContext.class)))
                .thenReturn(ToolResult.ok("plain text output", "done"));

        Map<String, Object> out = newExecutor(registry)
                .execute(toolStep("bash"), ctxWithWorkspace("/ws"));

        assertThat(out).containsKey("output");
        assertThat(out.get("output").toString()).contains("plain text output");
    }
}
