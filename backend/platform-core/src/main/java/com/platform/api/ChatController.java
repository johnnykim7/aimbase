package com.platform.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.platform.attachment.AttachmentException;
import com.platform.attachment.AttachmentService;
import com.platform.attachment.PdfTextExtractor;
import com.platform.attachment.PdfVisionResolver;
import com.platform.config.WorkspaceProperties;
import com.platform.domain.ChatAttachmentEntity;
import com.platform.llm.LLMAdapterRegistry;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.UnifiedMessage;
import com.platform.orchestrator.ChatRequest;
import com.platform.orchestrator.ChatResponse;
import com.platform.orchestrator.OrchestratorEngine;
import com.platform.session.CancellationRegistry;
import com.platform.tool.ToolFilterContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/chat")
@Tag(name = "Chat", description = "LLM 채팅 API")
public class ChatController {

    private final OrchestratorEngine orchestrator;
    private final WorkspaceProperties workspaceProperties;
    private final CancellationRegistry cancellationRegistry;
    private final AttachmentService attachmentService;
    private final PdfTextExtractor pdfTextExtractor;
    private final PdfVisionResolver pdfVisionResolver;
    private final LLMAdapterRegistry adapterRegistry;

    public ChatController(OrchestratorEngine orchestrator,
                          WorkspaceProperties workspaceProperties,
                          CancellationRegistry cancellationRegistry,
                          AttachmentService attachmentService,
                          PdfTextExtractor pdfTextExtractor,
                          PdfVisionResolver pdfVisionResolver,
                          LLMAdapterRegistry adapterRegistry) {
        this.orchestrator = orchestrator;
        this.workspaceProperties = workspaceProperties;
        this.cancellationRegistry = cancellationRegistry;
        this.attachmentService = attachmentService;
        this.pdfTextExtractor = pdfTextExtractor;
        this.pdfVisionResolver = pdfVisionResolver;
        this.adapterRegistry = adapterRegistry;
    }

    /**
     * CR-046: 진행 중 스트림 중지.
     * 활성 토큰이 있으면 cancel 플래그를 set → OrchestratorEngine/ToolCallHandler가 다음 체크포인트에서 break.
     */
    @PostMapping("/{sessionId}/abort")
    @PreAuthorize("hasAuthority('SCOPE_chat:stream') or isAuthenticated()")
    @Operation(summary = "스트림 중지", description = "진행 중인 SSE 스트림을 즉시 중지한다.")
    public ApiResponse<Map<String, Object>> abort(@PathVariable String sessionId) {
        boolean cancelled = cancellationRegistry.cancel(sessionId);
        if (!cancelled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "활성 스트림 없음: " + sessionId);
        }
        return ApiResponse.ok(Map.of("session_id", sessionId, "aborted", true));
    }

    @PostMapping("/completions")
    @Operation(summary = "채팅 완성 요청", description = "LLM 모델에 메시지를 전송하고 응답을 받는다. stream=true이면 SSE로 응답.")
    public Object completions(@Valid @RequestBody ChatCompletionRequest request) {
        // CR-045 L0: working_directory 화이트리스트 조기 차단 (400)
        validateWorkingDirectory(request.workingDirectory());

        AdapterResolution resolved = resolveAdapter(request.model());
        List<UnifiedMessage> messages = request.messages().stream()
                .map(dto -> toUnifiedMessage(dto, request.sessionId(), resolved))
                .toList();

        // CR-007: response_format → ChatRequest.ResponseFormat 변환
        ChatRequest.ResponseFormat responseFormat = null;
        if (request.responseFormat() != null && "json_schema".equals(request.responseFormat().type())) {
            responseFormat = new ChatRequest.ResponseFormat(
                    request.responseFormat().type(),
                    request.responseFormat().schemaRef(),
                    request.responseFormat().schema()
            );
        }

        // CR-006: tool_filter → ToolFilterContext 변환
        ToolFilterContext toolFilter = null;
        if (request.toolFilter() != null) {
            toolFilter = new ToolFilterContext(
                    request.toolFilter().allowedTools(),
                    request.toolFilter().excludeTools(),
                    null
            );
        }

        ChatRequest chatRequest = new ChatRequest(
                request.model(),
                request.sessionId(),
                messages,
                request.stream(),
                request.actionsEnabled(),
                null,
                null,
                request.connectionId(),
                toolFilter,
                request.toolChoice(),
                responseFormat,
                request.connectionGroupId(),
                request.workingDirectory()
        );

        if (request.stream()) {
            return streamResponse(chatRequest);
        }
        try {
            ChatResponse response = orchestrator.chat(chatRequest);
            return ApiResponse.ok(toChatCompletionResponse(response));
        } catch (IllegalStateException e) {
            // CR-045 BIZ-091: 세션 workspaceRef 충돌 → 409
            if (e.getMessage() != null && e.getMessage().contains("workspaceRef 충돌")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
            }
            throw e;
        }
    }

    private SseEmitter streamResponse(ChatRequest chatRequest) {
        SseEmitter emitter = new SseEmitter(300_000L);
        // CR-045: ThreadLocal은 VT별 독립 → 부모 요청 스레드의 TenantContext를
        // 가상 스레드에 수동 전파해야 Hibernate DataSource 라우팅이 테넌트 DB로 감.
        final String propagatedTenantId = com.platform.tenant.TenantContext.getTenantId();
        final org.springframework.security.core.context.SecurityContext propagatedSecurityContext =
                org.springframework.security.core.context.SecurityContextHolder.getContext();
        // CR-075: ClaudeCliAdapter 자동 라우팅에 필요한 user_ref / agent_id 도 VT 로 전파.
        final String propagatedAgentId = com.platform.llm.adapter.RequestContext.getAgentId();
        final String propagatedUserRef = com.platform.llm.adapter.RequestContext.getUserRef();
        Thread.ofVirtual().start(() -> {
            if (propagatedTenantId != null) {
                com.platform.tenant.TenantContext.setTenantId(propagatedTenantId);
            }
            org.springframework.security.core.context.SecurityContextHolder.setContext(propagatedSecurityContext);
            if (propagatedAgentId != null) {
                com.platform.llm.adapter.RequestContext.setAgentId(propagatedAgentId);
            }
            if (propagatedUserRef != null) {
                com.platform.llm.adapter.RequestContext.setUserRef(propagatedUserRef);
            }

            // 단일 SSE 송출 람다 — orchestrator.chatStream과 SubagentRunner가 공유.
            // CR-053 Phase 2: SubagentStart/Done도 같은 emitter로 송출한다.
            java.util.function.Consumer<com.platform.orchestrator.stream.StreamEvent> sseSink = ev -> {
                try {
                    switch (ev) {
                        case com.platform.orchestrator.stream.StreamEvent.TextDelta t ->
                            emitter.send(SseEmitter.event()
                                    .name("delta")
                                    .data(Map.of("delta", t.delta() != null ? t.delta() : "")));
                        case com.platform.orchestrator.stream.StreamEvent.ThinkingDelta t ->
                            emitter.send(SseEmitter.event()
                                    .name("thinking")
                                    .data(Map.of("delta", t.delta() != null ? t.delta() : "")));
                        case com.platform.orchestrator.stream.StreamEvent.ToolUseStart s ->
                            emitter.send(SseEmitter.event()
                                    .name("tool_use_start")
                                    .data(Map.of("id", s.id(), "name", s.name(),
                                            "input", s.input() != null ? s.input() : Map.of())));
                        case com.platform.orchestrator.stream.StreamEvent.ToolResultEvent r ->
                            emitter.send(SseEmitter.event()
                                    .name("tool_result")
                                    .data(Map.of("tool_use_id", r.toolUseId(),
                                            "output", r.output() != null ? r.output() : "",
                                            "is_error", r.isError())));
                        case com.platform.orchestrator.stream.StreamEvent.Done d -> {
                            Map<String, Object> donePayload = new HashMap<>();
                            donePayload.put("done", true);
                            if (d.citations() != null && !d.citations().isEmpty()) {
                                donePayload.put("citations", d.citations());
                            }
                            if (Boolean.TRUE.equals(d.ragUsed())) {
                                donePayload.put("rag_used", true);
                            }
                            emitter.send(SseEmitter.event()
                                    .name("done")
                                    .data(donePayload));
                            emitter.complete();
                        }
                        case com.platform.orchestrator.stream.StreamEvent.SubagentStart s ->
                            emitter.send(SseEmitter.event()
                                    .name("subagent_start")
                                    .data(Map.of(
                                            "run_id", s.runId(),
                                            "agent_type", s.agentType(),
                                            "description", s.description() != null ? s.description() : "")));
                        case com.platform.orchestrator.stream.StreamEvent.SubagentDone sd ->
                            emitter.send(SseEmitter.event()
                                    .name("subagent_done")
                                    .data(Map.of(
                                            "run_id", sd.runId(),
                                            "status", sd.status(),
                                            "summary", sd.summary() != null ? sd.summary() : "",
                                            "duration_ms", sd.durationMs())));
                    }
                } catch (IOException e) {
                    emitter.completeWithError(e);
                }
            };

            // CR-053 Phase 2: SubagentRunner가 emit()으로 발행한 이벤트가 같은 SSE emitter로 가도록 싱크 등록.
            // 백그라운드 서브에이전트는 자식 VT에서 Done을 발행하므로, 람다가 부모 emitter를 클로저로 캡처해서 전달.
            com.platform.agent.SubagentRunner.setStreamSink(sseSink);
            try {
                orchestrator.chatStream(chatRequest, sseSink);
            } catch (Exception e) {
                // CR-082 (HOTFIX 2026-04-30): ResponseStatusException 도 일단 completeWithError 로 처리.
                // emitter.complete() 흐름이 ASYNC dispatch 시 Spring Security 권한 재검사 통과 못 하는 케이스 관찰됨.
                // SSE error event 표준화는 SecurityContext 흐름 정리 후 별도 작업.
                emitter.completeWithError(e);
            } finally {
                com.platform.agent.SubagentRunner.clearStreamSink();
                com.platform.tenant.TenantContext.clear();
                // CR-082 root fix (2026-04-30): SecurityContextHolder.clearContext() 를 호출하면
                // emitter.completeWithError(e) 가 트리거하는 Spring async dispatch 가
                // 빈 SecurityContext 로 진입해 AuthorizationFilter 에서 거부 → 위젯에 403 으로 표시되는 race.
                // VT 가 종료되면 ThreadLocal 은 자동 회수되므로 명시 clear 는 불필요. 같은 이유로 RequestContext.clear() 도 보류.
                // Tenant 는 다음 요청 진입 시 TenantResolver 가 다시 채워주므로 clear 안전.
            }
        });
        return emitter;
    }

    private Map<String, Object> toChatCompletionResponse(ChatResponse response) {
        // CR-007: content 블록을 타입별로 변환
        List<Map<String, Object>> contentBlocks = new ArrayList<>();
        for (ContentBlock block : response.content()) {
            if (block instanceof ContentBlock.Structured s) {
                Map<String, Object> structured = new HashMap<>();
                structured.put("type", "structured");
                structured.put("schema", s.schema());
                structured.put("data", s.data());
                contentBlocks.add(structured);
            } else if (block instanceof ContentBlock.Text t) {
                Map<String, Object> text = new HashMap<>();
                text.put("type", "text");
                text.put("text", t.text());
                contentBlocks.add(text);
            }
        }
        // 빈 경우 빈 텍스트 블록 추가
        if (contentBlocks.isEmpty()) {
            contentBlocks.add(Map.of("type", "text", "text", ""));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("id", response.id());
        result.put("model", response.model());
        result.put("session_id", response.sessionId());
        result.put("content", contentBlocks);
        result.put("actions_executed", response.actionsExecuted());
        Map<String, Object> usageMap = new java.util.LinkedHashMap<>();
        usageMap.put("input_tokens", response.usage().inputTokens());
        usageMap.put("output_tokens", response.usage().outputTokens());
        usageMap.put("cost_usd", response.costUsd());
        if (response.usage().cacheCreationInputTokens() > 0) {
            usageMap.put("cache_creation_input_tokens", response.usage().cacheCreationInputTokens());
        }
        if (response.usage().cacheReadInputTokens() > 0) {
            usageMap.put("cache_read_input_tokens", response.usage().cacheReadInputTokens());
        }
        result.put("usage", usageMap);
        return result;
    }

    // ─── Request/Response DTOs ───

    public record ChatCompletionRequest(
            String model,
            @JsonProperty("session_id") String sessionId,
            @NotEmpty List<MessageDto> messages,
            boolean stream,
            @JsonProperty("actions_enabled") boolean actionsEnabled,
            @JsonProperty("connection_id") String connectionId,
            @JsonProperty("connection_group_id") String connectionGroupId,
            @JsonProperty("tool_filter") ToolFilterDto toolFilter,
            @JsonProperty("tool_choice") String toolChoice,
            @JsonProperty("response_format") ResponseFormatDto responseFormat,
            @JsonProperty("working_directory") String workingDirectory
    ) {}

    /** CR-006: 도구 필터링 DTO */
    public record ToolFilterDto(
            @JsonProperty("allowed_tools") List<String> allowedTools,
            @JsonProperty("exclude_tools") List<String> excludeTools
    ) {}

    /** CR-007: 구조화된 출력 요청 DTO */
    public record ResponseFormatDto(
            String type,
            @JsonProperty("schema_ref") String schemaRef,
            Map<String, Object> schema
    ) {}

    /**
     * PRD-111: 멀티모달 메시지 DTO.
     * content는 String(텍스트) 또는 List(멀티모달 파트 배열) 모두 허용.
     */
    public record MessageDto(
            @NotBlank String role,
            Object content
    ) {}

    /**
     * PRD-111: content 파트 (멀티모달 입력 시 List 원소).
     * type="text" → text 필드, type="image_url" → imageUrl 필드
     */
    public record ContentPartDto(
            String type,
            String text,
            @JsonProperty("image_url") ImageUrlDto imageUrl
    ) {}

    public record ImageUrlDto(
            String url
    ) {}

    // ─── CR-045 L0: 화이트리스트 조기 차단 ───

    private void validateWorkingDirectory(String workingDirectory) {
        if (workingDirectory == null || workingDirectory.isBlank()) return;
        Path expanded = Path.of(expandHome(workingDirectory)).toAbsolutePath().normalize();
        if (!workspaceProperties.isInsideWhitelist(expanded)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "working_directory가 화이트리스트 외부: " + workingDirectory);
        }
    }

    private static String expandHome(String raw) {
        if (raw.startsWith("~")) {
            return System.getProperty("user.home") + raw.substring(1);
        }
        return raw;
    }

    // ─── 멀티모달 변환 헬퍼 ───

    @SuppressWarnings("unchecked")
    private UnifiedMessage toUnifiedMessage(MessageDto dto, String sessionId, AdapterResolution adapter) {
        UnifiedMessage.Role role = UnifiedMessage.Role.valueOf(dto.role().toUpperCase());

        if (dto.content() instanceof String text) {
            return UnifiedMessage.ofText(role, text);
        }

        if (dto.content() instanceof List<?> parts) {
            List<ContentBlock> blocks = new ArrayList<>();
            // CR-061: PDF 폴백 텍스트는 유저 메시지 맨 앞에 prepend 하기 위해 누적.
            StringBuilder pdfFallbackPrefix = new StringBuilder();

            for (Object part : parts) {
                if (!(part instanceof Map<?, ?> map)) continue;
                String type = (String) map.get("type");

                if ("text".equals(type)) {
                    String text = (String) map.get("text");
                    if (text != null) blocks.add(new ContentBlock.Text(text));

                } else if ("image".equals(type)) {
                    // CR-061: 새 이미지 블록 — attachment_id 참조 or 인라인 data
                    ContentBlock img = resolveImageBlock(map, sessionId, adapter);
                    if (img != null) blocks.add(img);

                } else if ("document".equals(type)) {
                    // CR-061: PDF 첨부 — adapter.pdf 지원 여부에 따라 Document or 텍스트 폴백
                    resolveDocumentBlock(map, sessionId, adapter, blocks, pdfFallbackPrefix);

                } else if ("image_url".equals(type)) {
                    // PRD-111 호환: OpenAI 스타일 data: URI 또는 URL
                    Map<?, ?> imageUrl = (Map<?, ?>) map.get("image_url");
                    if (imageUrl != null) {
                        String url = (String) imageUrl.get("url");
                        if (url != null && url.startsWith("data:")) {
                            int semicolonIdx = url.indexOf(';');
                            int commaIdx = url.indexOf(',');
                            String mediaType = url.substring(5, semicolonIdx);
                            String base64Data = url.substring(commaIdx + 1);
                            blocks.add(ContentBlock.Image.ofBase64(mediaType, base64Data));
                        } else if (url != null) {
                            blocks.add(ContentBlock.Image.ofUrl(url, "image/jpeg"));
                        }
                    }
                }
            }

            // PDF 폴백 텍스트가 있으면 맨 앞에 1개 Text 블록으로 삽입
            if (pdfFallbackPrefix.length() > 0) {
                blocks.add(0, new ContentBlock.Text(pdfFallbackPrefix.toString()));
            }
            if (blocks.isEmpty()) {
                return UnifiedMessage.ofText(role, "");
            }
            return new UnifiedMessage(role, blocks);
        }

        return UnifiedMessage.ofText(role, dto.content() != null ? dto.content().toString() : "");
    }

    /** CR-061: image 블록 해석. attachment_id 우선, 없으면 인라인 data/url. */
    private ContentBlock resolveImageBlock(Map<?, ?> map, String sessionId, AdapterResolution adapter) {
        String attachmentId = (String) map.get("attachment_id");
        if (attachmentId != null && !attachmentId.isBlank()) {
            if (!adapter.capability().supportsImage()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "selected adapter does not support image input");
            }
            ChatAttachmentEntity att = attachmentService.loadOwned(UUID.fromString(attachmentId), sessionId);
            byte[] bytes = attachmentService.readBytes(att);
            String base64 = Base64.getEncoder().encodeToString(bytes);
            return ContentBlock.Image.ofBase64(att.getMediaType(), base64);
        }
        String inlineData = (String) map.get("data");
        String mediaType = (String) map.get("media_type");
        if (inlineData != null && mediaType != null) {
            return ContentBlock.Image.ofBase64(mediaType, inlineData);
        }
        String url = (String) map.get("url");
        if (url != null) {
            return ContentBlock.Image.ofUrl(url, mediaType != null ? mediaType : "image/jpeg");
        }
        return null;
    }

    /**
     * CR-061 + CR-095: document 블록 해석 — PDF 비전 게이트(openclaude 1:1).
     * ≤3MB & PDF 지원 → document block 통째 / >3MB or 미지원 → 페이지 이미지화 → image 블록 /
     * 이미지도 미지원 → PdfTextExtractor 텍스트 폴백.
     */
    private void resolveDocumentBlock(Map<?, ?> map, String sessionId, AdapterResolution adapter,
                                       List<ContentBlock> blocks, StringBuilder pdfFallbackPrefix) {
        String attachmentId = (String) map.get("attachment_id");
        if (attachmentId == null || attachmentId.isBlank()) {
            throw new AttachmentException(HttpStatus.BAD_REQUEST,
                    AttachmentException.CODE_NOT_FOUND,
                    "document block requires attachment_id");
        }
        ChatAttachmentEntity att = attachmentService.loadOwned(UUID.fromString(attachmentId), sessionId);
        byte[] bytes = attachmentService.readBytes(att);

        // PDF 외(미래 확장)는 기존 path 유지 — 현재 document 블록은 PDF 만 들어온다.
        PdfVisionResolver.PdfVisionResult vision = pdfVisionResolver.resolve(
                bytes, adapter.capability().supportsPdf(), adapter.capability().supportsImage());

        switch (vision.mode()) {
            case INLINE_PDF -> blocks.add(
                    ContentBlock.Document.ofBase64(att.getMediaType(), vision.pdfBase64(), att.getFilename()));
            case PAGE_IMAGES -> {
                if (vision.note() != null) {
                    blocks.add(new ContentBlock.Text("[첨부 PDF: " + att.getFilename() + " — " + vision.note() + "]"));
                }
                for (PdfVisionResolver.PageImage img : vision.images()) {
                    blocks.add(ContentBlock.Image.ofBase64(img.mediaType(), img.base64()));
                }
            }
            case TEXT_FALLBACK -> {
                PdfTextExtractor.ExtractResult extracted = pdfTextExtractor.extract(bytes);
                pdfFallbackPrefix
                        .append("[첨부 문서: ").append(att.getFilename()).append("]\n")
                        .append(extracted.isEmpty() ? "(텍스트 추출 실패)" : extracted.text())
                        .append("\n\n");
            }
            // 첨부 경로는 allowSplitGuard=false 라 NEEDS_SPLIT 도달 불가(1회성, 모델 재호출 불가).
            // 방어적: 도달 시 첫 페이지들이라도 보이게 PAGE_IMAGES 동등 처리.
            case NEEDS_SPLIT -> {
                for (PdfVisionResolver.PageImage img : vision.images()) {
                    blocks.add(ContentBlock.Image.ofBase64(img.mediaType(), img.base64()));
                }
            }
        }
    }

    /** CR-061: 모델 문자열 → 어댑터 + capability. 모델 미지정("auto") 시 anthropic 기본값. */
    private AdapterResolution resolveAdapter(String model) {
        String modelId = model != null && !model.isBlank() && !"auto".equalsIgnoreCase(model)
                ? model : "anthropic/claude-sonnet-4-5";
        if (!adapterRegistry.hasAdapter(modelId)) {
            // 등록되지 않은 모델이면 image/pdf 미지원으로 취급 — orchestrator 레이어에서 라우팅 재해석.
            return new AdapterResolution(modelId, com.platform.llm.adapter.AdapterCapability.NONE);
        }
        LLMAdapter adapter = adapterRegistry.getAdapter(modelId);
        return new AdapterResolution(modelId, adapter.capabilities());
    }

    private record AdapterResolution(String modelId, com.platform.llm.adapter.AdapterCapability capability) {}
}
