package com.platform.mcp.server;

import com.platform.attachment.PdfVisionResolver;
import com.platform.hook.HookDispatcher;
import com.platform.hook.HookEvent;
import com.platform.hook.HookOutput;
import com.platform.llm.adapter.RequestContext;
import com.platform.orchestrator.approval.ToolApprovalGate;
import com.platform.policy.TokenBucketRateLimiter;
import com.platform.tool.EnhancedToolExecutor;
import com.platform.tool.PermissionLevel;
import com.platform.tool.RetryPolicy;
import com.platform.tool.ToolContext;
import com.platform.tool.ToolContractMeta;
import com.platform.tool.ToolExecutor;
import com.platform.tool.ToolMessageBlock;
import com.platform.tool.ToolRegistry;
import com.platform.tool.ToolResult;
import com.platform.tool.ToolScope;
import com.platform.mcp.MCPToolErrorException;
import com.platform.tool.model.UnifiedToolDef;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServerMcpToolDispatcherTest {

    private HookDispatcher hookDispatcher;
    private TokenBucketRateLimiter rateLimiter;
    private PdfVisionResolver pdfVisionResolver;
    private ToolRegistry toolRegistry;
    private ServerMcpToolDispatcher dispatcher;
    private ToolApprovalGate approvalGate;

    @BeforeEach
    void setUp() {
        hookDispatcher = mock(HookDispatcher.class);
        rateLimiter = mock(TokenBucketRateLimiter.class);
        pdfVisionResolver = mock(PdfVisionResolver.class);
        toolRegistry = mock(ToolRegistry.class);
        // CR-121: 노출 게이트 소스가 McpExposurePolicy → ToolRegistry.isCliExposed 로 이동.
        // 목 동작은 동일 — team_create(내부전용)만 차단, 나머지는 통과. 이 테스트는 dispatcher 의
        // Hook/RateLimit/PDF 렌더링 동작 검증이 목적이며, 노출 정책 자체 검증은 McpExposurePolicyTest
        // 와 ToolRegistryExposureFilterTest 담당.
        when(toolRegistry.isCliExposed(any(ToolExecutor.class))).thenAnswer(inv -> {
            ToolExecutor t = inv.getArgument(0);
            return !"team_create".equals(t.getDefinition().name());
        });
        // 기본: PASSTHROUGH 반환 (BLOCK 아님)
        when(hookDispatcher.dispatch(any(HookEvent.class), any(), any(String.class)))
                .thenReturn(HookOutput.PASSTHROUGH);
        // 기본: rate limit 통과
        when(rateLimiter.tryAcquire(anyString(), anyInt()))
                .thenReturn(TokenBucketRateLimiter.RateLimitResult.allowed(60, 59));
        approvalGate = mock(ToolApprovalGate.class);
        when(approvalGate.check(any(), any(), any(), any())).thenReturn(ToolApprovalGate.Verdict.go());
        dispatcher = new ServerMcpToolDispatcher(hookDispatcher, rateLimiter, pdfVisionResolver,
                toolRegistry, approvalGate, 60);
    }

    @AfterEach
    void clearRequestContext() {
        RequestContext.clear();
    }

    @Test
    @DisplayName("CR-185 TC-CHT-116: 승인 문이 막으면 도구를 실행하지 않고 사유를 오류 결과로 돌려준다")
    void approval_gate_refusal_skips_tool() {
        ToolExecutor tool = mock(ToolExecutor.class);
        when(tool.getDefinition()).thenReturn(new UnifiedToolDef("refund_order", "환불", Map.of("type", "object")));
        when(toolRegistry.isCliExposed(tool)).thenReturn(true);
        when(approvalGate.check(eq("refund_order"), any(), any(), eq("sess-9")))
                .thenReturn(ToolApprovalGate.Verdict.refuse("사용자가 실행을 거절했습니다"));
        RequestContext.setChatSessionId("sess-9");

        McpSchema.CallToolResult result = dispatcher.dispatch(tool, "refund_order", Map.of("order_id", "A-1"));

        assertThat(result.isError()).isTrue();
        assertThat(((McpSchema.TextContent) result.content().get(0)).text()).contains("사용자가 실행을 거절했습니다");
        verify(tool, never()).execute(any());
    }

    @Test
    void rejects_non_cli_exposed_tool() {
        ToolExecutor tool = stubTool("team_create", "{\"ok\":true}");

        McpSchema.CallToolResult result = dispatcher.dispatch(tool, "team_create", Map.of());

        assertThat(result.isError()).isTrue();
        // PRE_TOOL_USE Hook 도 호출 안 됨
        verify(hookDispatcher, times(0)).dispatch(eq(HookEvent.PRE_TOOL_USE), any(), any(String.class));
    }

    @Test
    void dispatch_by_name_uses_currently_registered_executor() {
        // CR-146: 재연결이 같은 이름의 실행기를 바꿔 끼우면 다음 호출은 새 실행기를 탄다.
        when(toolRegistry.getExecutor("get_opportunity"))
                .thenReturn(stubTool("get_opportunity", "old-connection"))
                .thenReturn(stubTool("get_opportunity", "new-connection"));

        dispatcher.dispatch("get_opportunity", Map.of());
        McpSchema.CallToolResult result = dispatcher.dispatch("get_opportunity", Map.of());

        assertThat(result.isError()).isFalse();
        assertThat(((McpSchema.TextContent) result.content().get(0)).text()).isEqualTo("new-connection");
    }

    @Test
    @DisplayName("IT-01-008 1 큰_결과를_자르지_않고_돌려준다")
    void cr153_large_result_is_passed_through_untruncated() {
        // TC-MCP-RES-001: 8,000자로 자르면 가운데를 되찾을 길이 없다. 크기는 도구 목록의 상한으로 CLI 가 정한다.
        String big = "x".repeat(81_881);
        McpSchema.CallToolResult result = dispatcher.dispatch(stubTool("get_proposal_design_input", big),
                "get_proposal_design_input", Map.of());

        assertThat(result.isError()).isFalse();
        assertThat(((McpSchema.TextContent) result.content().get(0)).text()).isEqualTo(big);
    }

    @Test
    void dispatch_by_name_returns_error_when_tool_is_gone() {
        when(toolRegistry.getExecutor("get_opportunity")).thenReturn(null);

        McpSchema.CallToolResult result = dispatcher.dispatch("get_opportunity", Map.of());

        assertThat(result.isError()).isTrue();
        verify(hookDispatcher, times(0)).dispatch(eq(HookEvent.PRE_TOOL_USE), any(), any(String.class));
    }

    @Test
    void cli_exposed_tool_executes_with_pre_post_hooks() {
        ToolExecutor tool = stubTool("web_search", "{\"results\":[]}");

        McpSchema.CallToolResult result = dispatcher.dispatch(tool, "web_search", Map.of("q", "test"));

        assertThat(result.isError()).isFalse();
        verify(hookDispatcher, times(1)).dispatch(eq(HookEvent.PRE_TOOL_USE), any(), eq("web_search"));
        verify(hookDispatcher, times(1)).dispatch(eq(HookEvent.POST_TOOL_USE), any(), eq("web_search"));
    }

    @Test
    void cr127_remote_tool_error_is_reported_as_isError_to_cli() {
        // CR-127: 원격 도구가 isError 로 실패하면 MCPToolErrorException 으로 승격되어
        // 디스패처가 errorResult 를 만든다. 이전에는 에러 텍스트가 정상 결과로 둔갑해
        // CLI 에 isError:false 로 나갔고, 모델이 없는 데이터를 창작했다.
        UnifiedToolDef def = new UnifiedToolDef("get_progress", "진척 조회", Map.of("type", "object"));
        ToolExecutor failing = new ToolExecutor() {
            @Override
            public UnifiedToolDef getDefinition() { return def; }

            @Override
            public String execute(Map<String, Object> args) {
                throw new MCPToolErrorException("get_progress", "date must not be null");
            }
        };

        McpSchema.CallToolResult result = dispatcher.dispatch(failing, "get_progress", Map.of());

        assertThat(result.isError()).isTrue();
        assertThat(((McpSchema.TextContent) result.content().get(0)).text())
                .contains("date must not be null");
        // 실패는 POST_TOOL_USE 가 아니라 실패 훅으로 간다
        verify(hookDispatcher, times(1))
                .dispatch(eq(HookEvent.POST_TOOL_USE_FAILURE), any(), eq("get_progress"));
        verify(hookDispatcher, times(0))
                .dispatch(eq(HookEvent.POST_TOOL_USE), any(), eq("get_progress"));
    }

    @Test
    void pre_hook_block_aborts_execution() {
        when(hookDispatcher.dispatch(eq(HookEvent.PRE_TOOL_USE), any(), eq("web_search")))
                .thenReturn(HookOutput.block("denied"));

        ToolExecutor tool = stubTool("web_search", "should-not-run");
        McpSchema.CallToolResult result = dispatcher.dispatch(tool, "web_search", Map.of());

        assertThat(result.isError()).isTrue();
        // POST_TOOL_USE 는 호출 안 됨
        verify(hookDispatcher, times(0)).dispatch(eq(HookEvent.POST_TOOL_USE), any(), any(String.class));
    }

    @Test
    void rate_limit_exceeded_aborts_execution() {
        when(rateLimiter.tryAcquire(anyString(), anyInt()))
                .thenReturn(TokenBucketRateLimiter.RateLimitResult.exceeded(60, 100, 30));

        ToolExecutor tool = stubTool("web_search", "should-not-run");
        McpSchema.CallToolResult result = dispatcher.dispatch(tool, "web_search", Map.of());

        assertThat(result.isError()).isTrue();
        // Hook 도 호출 안 됨 (rate limit 가 가장 먼저)
        verify(hookDispatcher, times(0)).dispatch(eq(HookEvent.PRE_TOOL_USE), any(), any(String.class));
    }

    @Test
    void execution_failure_dispatches_post_failure_hook() {
        ToolExecutor tool = new ToolExecutor() {
            @Override
            public UnifiedToolDef getDefinition() {
                return new UnifiedToolDef("web_search", "test", Map.of("type", "object"));
            }

            @Override
            public String execute(Map<String, Object> args) {
                throw new RuntimeException("boom");
            }
        };

        McpSchema.CallToolResult result = dispatcher.dispatch(tool, "web_search", Map.of());

        assertThat(result.isError()).isTrue();
        verify(hookDispatcher, times(1)).dispatch(eq(HookEvent.POST_TOOL_USE_FAILURE), any(), eq("web_search"));
        // 성공 Hook 은 호출 안 됨
        verify(hookDispatcher, times(0)).dispatch(eq(HookEvent.POST_TOOL_USE), any(), any(String.class));
    }

    // ── CR-101: EnhancedToolExecutor newMessages → MCP 멀티모달 content 운반 ──

    @Test
    void enhanced_tool_pdf_document_rendered_to_page_images() {
        // CLI 2.1.143 은 binary EmbeddedResource 를 인라인하지 않으므로 PDF → 페이지 이미지 변환이 정공
        when(pdfVisionResolver.resolve(any(byte[].class), eq(false), eq(true)))
                .thenReturn(new PdfVisionResolver.PdfVisionResult(
                        PdfVisionResolver.Mode.PAGE_IMAGES, null,
                        List.of(new PdfVisionResolver.PageImage(1, "image/jpeg", "UEFHRTFKUEVH"),
                                new PdfVisionResolver.PageImage(2, "image/jpeg", "UEFHRTJKUEVH")),
                        null, 2));
        ToolResult toolResult = new ToolResult(true,
                Map.of("mode", "inline_pdf"), "PDF attached for vision parsing",
                List.of(), List.of(), Map.of(), null, 0,
                List.of(
                        ToolMessageBlock.document("application/pdf", "UERGLUJBU0U2NA==", "report.pdf"),
                        ToolMessageBlock.image("image/png", "UE5HLUJBU0U2NA==")));
        ToolExecutor tool = stubEnhancedTool("parse_document", toolResult);

        McpSchema.CallToolResult result = dispatcher.dispatch(tool, "parse_document", Map.of());

        assertThat(result.isError()).isFalse();
        // [본문 텍스트, PDF 페이지 안내 텍스트, 페이지 이미지 2, 별도 image 블록] = 5
        assertThat(result.content()).hasSize(5);
        assertThat(result.content().get(0)).isInstanceOf(McpSchema.TextContent.class);
        assertThat(((McpSchema.TextContent) result.content().get(0)).text())
                .contains("PDF attached for vision parsing");

        assertThat(((McpSchema.TextContent) result.content().get(1)).text())
                .contains("report.pdf").contains("2 page image(s)");
        McpSchema.ImageContent page1 = (McpSchema.ImageContent) result.content().get(2);
        assertThat(page1.data()).isEqualTo("UEFHRTFKUEVH");
        assertThat(page1.mimeType()).isEqualTo("image/jpeg");
        assertThat(((McpSchema.ImageContent) result.content().get(3)).data()).isEqualTo("UEFHRTJKUEVH");

        McpSchema.ImageContent image = (McpSchema.ImageContent) result.content().get(4);
        assertThat(image.data()).isEqualTo("UE5HLUJBU0U2NA==");
        assertThat(image.mimeType()).isEqualTo("image/png");
    }

    @Test
    void enhanced_tool_pdf_render_failure_falls_back_to_embedded_resource() {
        when(pdfVisionResolver.resolve(any(byte[].class), eq(false), eq(true)))
                .thenReturn(new PdfVisionResolver.PdfVisionResult(
                        PdfVisionResolver.Mode.TEXT_FALLBACK, null, List.of(), "sidecar down", null));
        ToolResult toolResult = new ToolResult(true,
                Map.of("mode", "inline_pdf"), "PDF attached for vision parsing",
                List.of(), List.of(), Map.of(), null, 0,
                List.of(ToolMessageBlock.document("application/pdf", "UERGLUJBU0U2NA==", "report.pdf")));
        ToolExecutor tool = stubEnhancedTool("parse_document", toolResult);

        McpSchema.CallToolResult result = dispatcher.dispatch(tool, "parse_document", Map.of());

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).hasSize(2);
        McpSchema.EmbeddedResource resource = (McpSchema.EmbeddedResource) result.content().get(1);
        McpSchema.BlobResourceContents blob = (McpSchema.BlobResourceContents) resource.resource();
        assertThat(blob.blob()).isEqualTo("UERGLUJBU0U2NA==");
        assertThat(blob.mimeType()).isEqualTo("application/pdf");
        assertThat(blob.uri()).contains("report.pdf");
    }

    @Test
    void enhanced_tool_without_newMessages_returns_single_text_content() {
        ToolResult toolResult = new ToolResult(true,
                Map.of("text", "parsed body"), "Parsed document",
                List.of(), List.of(), Map.of(), null, 0);
        ToolExecutor tool = stubEnhancedTool("parse_document", toolResult);

        McpSchema.CallToolResult result = dispatcher.dispatch(tool, "parse_document", Map.of());

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0)).isInstanceOf(McpSchema.TextContent.class);
        assertThat(((McpSchema.TextContent) result.content().get(0)).text()).contains("parsed body");
    }

    @Test
    void enhanced_tool_failure_dispatches_post_failure_hook() {
        ToolExecutor tool = new EnhancedToolExecutor() {
            @Override
            public UnifiedToolDef getDefinition() {
                return new UnifiedToolDef("parse_document", "test", Map.of("type", "object"));
            }

            @Override
            public ToolContractMeta getContractMeta() {
                return contractMeta("parse_document");
            }

            @Override
            public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
                throw new RuntimeException("enhanced-boom");
            }
        };

        McpSchema.CallToolResult result = dispatcher.dispatch(tool, "parse_document", Map.of());

        assertThat(result.isError()).isTrue();
        verify(hookDispatcher, times(1)).dispatch(eq(HookEvent.POST_TOOL_USE_FAILURE), any(), eq("parse_document"));
    }

    // ── CR-117: 서버 MCP 도구가 run 격리 작업장(workspacePath)을 ToolContext 로 받는지 검증 ──

    @Test
    void cr117_workspace_header_propagated_into_toolcontext() {
        java.util.concurrent.atomic.AtomicReference<ToolContext> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        ToolExecutor tool = capturingEnhancedTool("file_write", captured);

        com.platform.llm.adapter.RequestContext.setWorkspacePath(
                "/data/workspace/bidding_system/runs/316d510c");
        try {
            McpSchema.CallToolResult result = dispatcher.dispatch(tool, "file_write", Map.of());
            assertThat(result.isError()).isFalse();
        } finally {
            com.platform.llm.adapter.RequestContext.clear();
        }

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().workspacePath())
                .isEqualTo("/data/workspace/bidding_system/runs/316d510c");
    }

    @Test
    void cr117_no_workspace_header_falls_back_to_null_workspacePath() {
        java.util.concurrent.atomic.AtomicReference<ToolContext> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        ToolExecutor tool = capturingEnhancedTool("file_write", captured);

        // RequestContext 미설정 → 기존 minimal(null,null) 동작 = workspacePath null
        com.platform.llm.adapter.RequestContext.clear();
        McpSchema.CallToolResult result = dispatcher.dispatch(tool, "file_write", Map.of());

        assertThat(result.isError()).isFalse();
        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().workspacePath()).isNull();
    }

    // CR-147: 러너가 머리글로 돌려준 단계 고정 판이 스킬 도구의 ToolContext 에 닿는지

    @Test
    void cr147_skill_set_header_propagated_into_toolcontext() {
        java.util.concurrent.atomic.AtomicReference<ToolContext> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        ToolExecutor tool = capturingEnhancedTool("skill_open", captured);
        List<com.platform.tool.SkillRef> pins = List.of(new com.platform.tool.SkillRef("ax-proposal", "tenant", 1));

        com.platform.llm.adapter.RequestContext.setSkillSet(pins);
        try {
            assertThat(dispatcher.dispatch(tool, "skill_open", Map.of()).isError()).isFalse();
        } finally {
            com.platform.llm.adapter.RequestContext.clear();
        }

        assertThat(captured.get().skillSet()).isEqualTo(pins);
    }

    @Test
    void cr147_no_skill_set_header_leaves_toolcontext_without_pins() {
        java.util.concurrent.atomic.AtomicReference<ToolContext> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        ToolExecutor tool = capturingEnhancedTool("skill_open", captured);
        com.platform.llm.adapter.RequestContext.clear();

        dispatcher.dispatch(tool, "skill_open", Map.of());

        assertThat(captured.get().skillSet()).isNullOrEmpty();
    }

    @Test
    @DisplayName("CR-182 TC-CHT-083: 통로 도구 머리글이 없으면 aimbase-data 통로는 data_query 만 부른다")
    void dataChannel_withoutHeader_blocksCreateFile() {
        when(toolRegistry.getExecutor("create_file")).thenReturn(stubTool("create_file", "made"));
        when(toolRegistry.getExecutor("data_query")).thenReturn(stubTool("data_query", "rows"));
        com.platform.llm.adapter.RequestContext.clear();
        try {
            assertThat(dispatcher.dispatchData("create_file", Map.of()).isError()).isTrue();
            assertThat(dispatcher.dispatchData("data_query", Map.of()).isError()).isFalse();
        } finally {
            com.platform.llm.adapter.RequestContext.clear();
        }
    }

    @Test
    @DisplayName("CR-182 TC-CHT-083: 머리글로 연 create_file 은 대화 세션으로 부르고 작업 폴더는 비운다")
    void dataChannel_createFile_getsChatSession() {
        java.util.concurrent.atomic.AtomicReference<ToolContext> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(toolRegistry.getExecutor("create_file")).thenReturn(capturingEnhancedTool("create_file", captured));
        com.platform.llm.adapter.RequestContext.setChannelTools("create_file");
        com.platform.llm.adapter.RequestContext.setChatSessionId("cs-1");
        try {
            assertThat(dispatcher.dispatchData("create_file", Map.of()).isError()).isFalse();
            assertThat(dispatcher.dispatchData("data_query", Map.of()).isError()).isTrue();
        } finally {
            com.platform.llm.adapter.RequestContext.clear();
        }

        assertThat(captured.get().sessionId()).isEqualTo("cs-1");
        assertThat(captured.get().workspacePath()).isNull();
    }

    private static ToolExecutor capturingEnhancedTool(
            String name, java.util.concurrent.atomic.AtomicReference<ToolContext> sink) {
        UnifiedToolDef def = new UnifiedToolDef(name, "test", Map.of("type", "object"));
        ToolResult result = new ToolResult(true, Map.of("ok", true), "wrote",
                List.of(), List.of(), Map.of(), null, 0);
        return new EnhancedToolExecutor() {
            @Override
            public UnifiedToolDef getDefinition() { return def; }

            @Override
            public ToolContractMeta getContractMeta() { return contractMeta(name); }

            @Override
            public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
                sink.set(ctx);
                return result;
            }
        };
    }

    private static ToolExecutor stubEnhancedTool(String name, ToolResult result) {
        UnifiedToolDef def = new UnifiedToolDef(name, "test", Map.of("type", "object"));
        return new EnhancedToolExecutor() {
            @Override
            public UnifiedToolDef getDefinition() { return def; }

            @Override
            public ToolContractMeta getContractMeta() { return contractMeta(name); }

            @Override
            public ToolResult execute(Map<String, Object> input, ToolContext ctx) { return result; }
        };
    }

    private static ToolContractMeta contractMeta(String name) {
        return new ToolContractMeta(
                name, "1.0", ToolScope.BUILTIN,
                PermissionLevel.READ_ONLY,
                false, true, false, true,
                RetryPolicy.NONE,
                List.of("test"), List.of("read"));
    }

    private static ToolExecutor stubTool(String name, String result) {
        UnifiedToolDef def = new UnifiedToolDef(name, "test", Map.of("type", "object"));
        return new ToolExecutor() {
            @Override
            public UnifiedToolDef getDefinition() { return def; }

            @Override
            public String execute(Map<String, Object> args) { return result; }
        };
    }
}
