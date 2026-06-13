package com.platform.mcp.server;

import com.platform.attachment.PdfVisionResolver;
import com.platform.hook.HookDecision;
import com.platform.hook.HookDispatcher;
import com.platform.hook.HookEvent;
import com.platform.hook.HookInput;
import com.platform.hook.HookOutput;
import com.platform.llm.adapter.RequestContext;
import com.platform.policy.TokenBucketRateLimiter;
import com.platform.tool.EnhancedToolExecutor;
import com.platform.tool.McpResultTruncator;
import com.platform.tenant.TenantContext;
import com.platform.tool.ToolContext;
import com.platform.tool.ToolExecutor;
import com.platform.tool.ToolMessageBlock;
import com.platform.tool.ToolResult;
import com.platform.tool.ToolResultRenderer;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CR-072: 서버 MCP endpoint 의 도구 호출 디스패처.
 *
 * <p>{@link com.platform.tool.ToolCallHandler} 가 OrchestratorEngine 경로에서 적용하는 거버넌스
 * (Hook PRE/POST_TOOL_USE) 와 동일 패턴을 MCP 진입 경로에도 적용한다. CR-050 트레이드오프 계승:
 * max_iterations / Plan Mode / SESSION_END 등 풀세트 Hook 은 적용 불가.</p>
 *
 * <p>요청 컨텍스트:
 * <ul>
 *   <li>{@link TenantContext#getTenantId()} — ApiKeyAuthenticationFilter 가 채워둠</li>
 *   <li>{@link RequestContext#getAgentIdOrNull()} — AgentIdRequestFilter 가 채워둠 (선택)</li>
 *   <li>sessionId 는 헤더로 전달 안 됨 → "mcp:" prefix + agent-id 로 합성하거나 null 처리</li>
 * </ul>
 * </p>
 */
@Component
public class ServerMcpToolDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ServerMcpToolDispatcher.class);

    private final HookDispatcher hookDispatcher;
    private final TokenBucketRateLimiter rateLimiter;
    private final PdfVisionResolver pdfVisionResolver;
    private final int rateLimitPerMinute;

    public ServerMcpToolDispatcher(HookDispatcher hookDispatcher,
                                    TokenBucketRateLimiter rateLimiter,
                                    PdfVisionResolver pdfVisionResolver,
                                    @Value("${mcp.rate-limit.requests-per-minute:60}")
                                    int rateLimitPerMinute) {
        this.hookDispatcher = hookDispatcher;
        this.rateLimiter = rateLimiter;
        this.pdfVisionResolver = pdfVisionResolver;
        this.rateLimitPerMinute = rateLimitPerMinute;
    }

    /**
     * MCP tools/call 진입점.
     *
     * <p>흐름:
     * <ol>
     *   <li>도구 노출 레벨 재검증 (이중 방어)</li>
     *   <li>PRE_TOOL_USE Hook — BLOCK 시 isError</li>
     *   <li>도구 실행 ({@link McpToolConversion} 의 dispatch 와 동일하지만 Hook 인지)</li>
     *   <li>POST_TOOL_USE / POST_TOOL_USE_FAILURE Hook</li>
     *   <li>결과 truncate</li>
     * </ol>
     * </p>
     */
    public McpSchema.CallToolResult dispatch(ToolExecutor tool, String name, Map<String, Object> args) {
        if (!McpExposurePolicy.isCliExposed(tool)) {
            log.warn("Server MCP: blocked non-CLI-exposed tool '{}'", name);
            return errorResult("Tool not exposed via MCP CLI channel");
        }

        // Rate Limit (테넌트 단위, MCP 채널 한정 키 스코프). 익명 호출은 'anon' 으로 묶임.
        String tenantKey = "mcp:" + nonNullOrAnon(TenantContext.getTenantId());
        TokenBucketRateLimiter.RateLimitResult rl = rateLimiter.tryAcquire(tenantKey, rateLimitPerMinute);
        if (!rl.allowed()) {
            log.warn("Server MCP: rate limit exceeded for tool '{}' tenant={} ({}/{}, retry={}s)",
                    name, tenantKey, rl.current(), rl.limit(), rl.retryAfterSeconds());
            return errorResult("Rate limit exceeded; retry after " + rl.retryAfterSeconds() + "s");
        }

        String sessionId = resolveSessionId();
        Map<String, Object> safeArgs = args == null ? Map.of() : args;

        HookOutput preHook = hookDispatcher.dispatch(
                HookEvent.PRE_TOOL_USE,
                HookInput.of(HookEvent.PRE_TOOL_USE, sessionId, name, safeArgs),
                name);
        if (preHook != null && preHook.decision() == HookDecision.BLOCK) {
            log.info("Server MCP: tool '{}' BLOCKED by PRE_TOOL_USE hook (session={})", name, sessionId);
            return errorResult("Tool execution blocked by policy hook");
        }

        String result;
        List<McpSchema.Content> mediaBlocks = List.of();
        try {
            if (tool instanceof EnhancedToolExecutor enhanced) {
                // CR-101: String execute() bridge 는 ToolResult.newMessages(PDF document/이미지 블록)를
                // 버린다 — parse_document PDF 비전 결과가 CLI 에 도달하지 못하는 원인.
                // ToolResult 를 직접 받아 텍스트는 bridge 와 동일하게 렌더하고(ToolResultRenderer),
                // newMessages 는 MCP 멀티모달 content 블록으로 변환해 같은 tool result 에 실어 보낸다.
                // ToolContext 는 bridge 와 동일하게 minimal(null, null) — 기존 경로와 행동 동일성 보존.
                ToolResult toolResult = enhanced.execute(safeArgs, ToolContext.minimal(null, null));
                result = ToolResultRenderer.render(toolResult);
                mediaBlocks = toMediaContent(toolResult.newMessages());
            } else {
                result = tool.execute(safeArgs);
            }
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            hookDispatcher.dispatch(
                    HookEvent.POST_TOOL_USE_FAILURE,
                    HookInput.of(HookEvent.POST_TOOL_USE_FAILURE, sessionId, name,
                            failureContext(msg)),
                    name);
            log.warn("Server MCP: tool '{}' execution failed: {}", name, msg);
            return errorResult(msg);
        }

        hookDispatcher.dispatch(
                HookEvent.POST_TOOL_USE,
                HookInput.of(HookEvent.POST_TOOL_USE, sessionId, name,
                        successContext(result)),
                name);

        String truncated = McpResultTruncator.truncate(name, result);
        if (mediaBlocks.isEmpty()) {
            return new McpSchema.CallToolResult(truncated, false);
        }
        // CR-101: 텍스트 + 멀티모달 블록 동시 운반. 텍스트만 truncate — base64 블록은 자르면 깨진다.
        McpSchema.CallToolResult.Builder builder = McpSchema.CallToolResult.builder()
                .addTextContent(truncated);
        mediaBlocks.forEach(builder::addContent);
        return builder.isError(false).build();
    }

    /**
     * CR-101: {@link ToolMessageBlock}(도구가 LLM 에 직접 보여줄 멀티모달 블록) → MCP content 변환.
     *
     * <ul>
     *   <li>image → {@link McpSchema.ImageContent} — claude CLI 가 인라인으로 모델 비전에 전달
     *       (2.1.109/2.1.143 실측 PASS)</li>
     *   <li>document(PDF) → <b>페이지 이미지화</b> 후 ImageContent 배열. claude CLI 2.1.143 은
     *       binary EmbeddedResource 를 모델에 인라인하지 않고 파일 저장 + 텍스트 포인터로 치환하는데,
     *       Runner 가 {@code --tools ""}(native Read 잠금) 로 CLI 를 띄우므로 저장 파일을 읽을 수 없다
     *       (운영 e2e 실측). 이미지 블록만이 MCP 채널에서 모델 비전에 닿는 유일한 인라인 형식.
     *       렌더 실패(TEXT_FALLBACK) 시 EmbeddedResource blob 으로 폴백 — NATIVE/HYBRID tool-mode
     *       CLI 는 저장 파일을 native Read(비전)로 읽을 수 있다.</li>
     *   <li>document(비-PDF) → {@link McpSchema.EmbeddedResource} + blob</li>
     *   <li>text → {@link McpSchema.TextContent}</li>
     * </ul>
     */
    private List<McpSchema.Content> toMediaContent(List<ToolMessageBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        List<McpSchema.Content> out = new ArrayList<>();
        for (ToolMessageBlock b : blocks) {
            if (b == null || b.data() == null) {
                continue;
            }
            String type = b.type() == null ? "" : b.type();
            switch (type) {
                case ToolMessageBlock.TYPE_IMAGE ->
                        out.add(new McpSchema.ImageContent(null, b.data(), b.mediaType()));
                case ToolMessageBlock.TYPE_DOCUMENT -> out.addAll(documentToContent(b));
                case ToolMessageBlock.TYPE_TEXT ->
                        out.add(new McpSchema.TextContent(b.data()));
                default -> log.warn("Server MCP: unsupported ToolMessageBlock type '{}' dropped", type);
            }
        }
        return out;
    }

    /** PDF document 블록 → 페이지 이미지(ImageContent) 우선, 실패 시 EmbeddedResource 폴백. */
    private List<McpSchema.Content> documentToContent(ToolMessageBlock b) {
        if ("application/pdf".equalsIgnoreCase(b.mediaType())) {
            try {
                byte[] pdfBytes = java.util.Base64.getDecoder().decode(b.data());
                // supportsPdf=false → PAGE_IMAGES 강제 (사이드카 pdf_to_images)
                PdfVisionResolver.PdfVisionResult vision = pdfVisionResolver.resolve(pdfBytes, false, true);
                if (vision.mode() == PdfVisionResolver.Mode.PAGE_IMAGES && !vision.images().isEmpty()) {
                    List<McpSchema.Content> pages = new ArrayList<>(vision.images().size() + 1);
                    pages.add(new McpSchema.TextContent(
                            "PDF '" + (b.filename() == null ? "document" : b.filename()) + "' rendered to "
                                    + vision.images().size() + " page image(s) below."
                                    + (vision.note() != null ? " (" + vision.note() + ")" : "")));
                    for (PdfVisionResolver.PageImage img : vision.images()) {
                        pages.add(new McpSchema.ImageContent(null, img.base64(), img.mediaType()));
                    }
                    return pages;
                }
                log.warn("Server MCP: PDF page rendering unavailable ({}) — falling back to embedded resource",
                        vision.note());
            } catch (Exception e) {
                log.warn("Server MCP: PDF page rendering failed — falling back to embedded resource: {}",
                        e.getMessage());
            }
        }
        return List.of(new McpSchema.EmbeddedResource(null, new McpSchema.BlobResourceContents(
                documentUri(b.filename()), b.mediaType(), b.data())));
    }

    /** EmbeddedResource 는 uri 필수 — 디스크 경로로 오인되지 않도록 가상 스킴을 쓴다. */
    private static String documentUri(String filename) {
        String safe = (filename == null || filename.isBlank()) ? "document" : filename;
        return "aimbase://tool-result/" + URLEncoder.encode(safe, StandardCharsets.UTF_8);
    }

    private String resolveSessionId() {
        String agentId = RequestContext.getAgentId();
        if (agentId != null && !agentId.isBlank()) {
            return "mcp:" + agentId;
        }
        String tenantId = TenantContext.getTenantId();
        return tenantId != null ? "mcp:" + tenantId : "mcp:anon";
    }

    private static String nonNullOrAnon(String s) {
        return (s == null || s.isBlank()) ? "anon" : s;
    }

    private static McpSchema.CallToolResult errorResult(String message) {
        String safe = message == null ? "" : message.replace("\"", "\\\"");
        return new McpSchema.CallToolResult("{\"error\":\"" + safe + "\"}", true);
    }

    private static Map<String, Object> failureContext(String error) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("error", error == null ? "unknown" : error);
        return ctx;
    }

    private static Map<String, Object> successContext(String result) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("result", result == null ? "" : truncatePreview(result, 500));
        return ctx;
    }

    private static String truncatePreview(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }
}
