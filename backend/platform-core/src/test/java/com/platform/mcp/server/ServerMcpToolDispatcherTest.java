package com.platform.mcp.server;

import com.platform.attachment.PdfVisionResolver;
import com.platform.hook.HookDispatcher;
import com.platform.hook.HookEvent;
import com.platform.hook.HookOutput;
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
import com.platform.tool.model.UnifiedToolDef;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServerMcpToolDispatcherTest {

    private HookDispatcher hookDispatcher;
    private TokenBucketRateLimiter rateLimiter;
    private PdfVisionResolver pdfVisionResolver;
    private ToolRegistry toolRegistry;
    private ServerMcpToolDispatcher dispatcher;

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
        dispatcher = new ServerMcpToolDispatcher(hookDispatcher, rateLimiter, pdfVisionResolver,
                toolRegistry, 60);
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
    void cli_exposed_tool_executes_with_pre_post_hooks() {
        ToolExecutor tool = stubTool("web_search", "{\"results\":[]}");

        McpSchema.CallToolResult result = dispatcher.dispatch(tool, "web_search", Map.of("q", "test"));

        assertThat(result.isError()).isFalse();
        verify(hookDispatcher, times(1)).dispatch(eq(HookEvent.PRE_TOOL_USE), any(), eq("web_search"));
        verify(hookDispatcher, times(1)).dispatch(eq(HookEvent.POST_TOOL_USE), any(), eq("web_search"));
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
