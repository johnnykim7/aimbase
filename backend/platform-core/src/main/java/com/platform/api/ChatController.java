package com.platform.api;

import com.platform.session.ChatRejectedException;
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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChatController.class);

    private final OrchestratorEngine orchestrator;
    private final WorkspaceProperties workspaceProperties;
    private final CancellationRegistry cancellationRegistry;
    private final AttachmentService attachmentService;
    private final PdfTextExtractor pdfTextExtractor;
    private final PdfVisionResolver pdfVisionResolver;
    private final LLMAdapterRegistry adapterRegistry;
    private final com.platform.artifact.ArtifactService artifactService;
    private final com.platform.llm.ConnectionAdapterFactory connectionAdapterFactory;
    private final com.platform.session.ConversationRewindService rewindService;
    private final com.platform.session.ChatWidgetSettingsService widgetSettings;
    private final com.platform.image.ImageGenerationService imageGeneration;
    private final com.platform.image.ImageGenerationSettings imageSettings;
    private final com.platform.session.ChatProjectService chatProjectService;
    private final com.platform.repository.ConversationSessionRepository sessionRepository;
    private final com.platform.attachment.data.AttachmentPromptBuilder attachmentPromptBuilder;
    private final com.platform.chatdoc.DocumentEditService documentEditService;

    /** CR-183 BIZ-223: render_features 값 모양과 개수 상한. */
    private static final java.util.regex.Pattern RENDER_FEATURE = java.util.regex.Pattern.compile("[a-z0-9-]{1,40}");
    private static final int MAX_RENDER_FEATURES = 10;

    public ChatController(OrchestratorEngine orchestrator,
                          WorkspaceProperties workspaceProperties,
                          CancellationRegistry cancellationRegistry,
                          AttachmentService attachmentService,
                          PdfTextExtractor pdfTextExtractor,
                          PdfVisionResolver pdfVisionResolver,
                          LLMAdapterRegistry adapterRegistry,
                          com.platform.artifact.ArtifactService artifactService,
                          com.platform.llm.ConnectionAdapterFactory connectionAdapterFactory,
                          com.platform.session.ConversationRewindService rewindService,
                          com.platform.session.ChatWidgetSettingsService widgetSettings,
                          com.platform.image.ImageGenerationService imageGeneration,
                          com.platform.image.ImageGenerationSettings imageSettings,
                          com.platform.session.ChatProjectService chatProjectService,
                          com.platform.repository.ConversationSessionRepository sessionRepository,
                          com.platform.attachment.data.AttachmentPromptBuilder attachmentPromptBuilder,
                          com.platform.chatdoc.DocumentEditService documentEditService) {
        this.chatProjectService = chatProjectService;
        this.sessionRepository = sessionRepository;
        this.attachmentPromptBuilder = attachmentPromptBuilder;
        this.orchestrator = orchestrator;
        this.workspaceProperties = workspaceProperties;
        this.cancellationRegistry = cancellationRegistry;
        this.attachmentService = attachmentService;
        this.pdfTextExtractor = pdfTextExtractor;
        this.pdfVisionResolver = pdfVisionResolver;
        this.adapterRegistry = adapterRegistry;
        this.artifactService = artifactService;
        this.connectionAdapterFactory = connectionAdapterFactory;
        this.rewindService = rewindService;
        this.widgetSettings = widgetSettings;
        this.imageGeneration = imageGeneration;
        this.imageSettings = imageSettings;
        this.documentEditService = documentEditService;
    }

    /**
     * CR-132: 세션 산출물(out/) 스캔 → done 페이로드/result 에 실을 artifacts 맵 목록.
     * 산출물 없으면 빈 목록. 스캔 실패는 서비스에서 흡수(빈 목록) — 응답을 막지 않는다.
     */
    private List<Map<String, Object>> scanArtifacts(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (com.platform.artifact.ArtifactService.ArtifactInfo a : artifactService.listArtifacts(sessionId)) {
            out.add(ArtifactController.artifactItem(sessionId, a));
        }
        return out;
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

        // CR-184: 그림 질문은 모델을 부르지 않는다(BIZ-231)
        if (request.image() != null) {
            return imageCompletion(request);
        }

        // CR-175: 모델, 깊게 생각하기 검사 뒤 되감기. 되감기는 스트림을 열기 전에 해야 오류를 HTTP 코드로 줄 수 있다.
        validateModel(request);
        Boolean thinkingOverride = parseThinking(request);
        // CR-178: 프로젝트는 응답을 시작하기 전에 거른다(PRD-424). 주인은 이 스레드에서 정해 실어 보낸다
        String owner = com.platform.session.ChatOwner.current();
        UUID chatProjectId = resolveChatProject(request, owner);
        List<String> renderFeatures = parseRenderFeatures(request.renderFeatures());   // CR-183
        rewindIfRequested(request);
        // CR-181: 옆 창 문서 고치기. 되감기 뒤에 준비해야 다시 생성이 지운 판을 바탕으로 쓰지 않는다.
        com.platform.chatdoc.PreparedEdit documentEdit = request.documentEdit() == null ? null
                : documentEditService.prepare(request.sessionId(), request.documentEdit(),
                        request.connectionId(), request.connectionGroupId());

        AdapterResolution resolved = resolveAdapter(request.model(), request.connectionId());
        // CR-183: 계산 도구가 붙는 요청이면 데이터 요약 끝에 계산 안내 틀을 붙인다(BIZ-221). 대화 API 요청은
        // CLI 면 언제나, API 면 데이터 파일이 있을 때 붙으므로 요청이 이 도구를 빼지 않았으면 붙는다.
        boolean dataGuide = request.toolFilter() == null || request.toolFilter().excludeTools() == null
                || !request.toolFilter().excludeTools().contains(com.platform.tool.builtin.DataQueryTool.NAME);
        List<UnifiedMessage> messages = request.messages().stream()
                .map(dto -> toUnifiedMessage(dto, request.sessionId(), resolved, dataGuide))
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
                request.workingDirectory(),
                // CR-128(갭B): 요청 단위 CLI override 전달
                request.toolMode(),
                request.systemPromptOverride()
        ).withThinkingOverride(thinkingOverride)
                .withWidgetTurn(new ChatRequest.WidgetTurn(owner, chatProjectId, questionDisplay(request),
                        com.platform.session.ChatOwner.isWidget(com.platform.session.ChatOwner.currentPrincipal())))
                // CR-183: 대화 API 요청 표시(계산 도구 붙이기)와 위젯이 그릴 수 있는 형식(BIZ-222, BIZ-223)
                .withChatFeatures(true, renderFeatures)
                .withFileCreate(Boolean.TRUE.equals(request.fileCreate()) || (documentEdit != null && documentEdit.fileCreate()))
                .withTurnInstruction(documentEdit != null ? documentEdit.instruction() : null);

        if (request.stream()) {
            return streamResponse(chatRequest, documentEdit);
        }
        ChatResponse response;
        try {
            response = orchestrator.chat(chatRequest);
        } catch (IllegalStateException e) {
            documentEditService.abandon(documentEdit);
            // CR-045 BIZ-091: 세션 workspaceRef 충돌 → 409
            if (e.getMessage() != null && e.getMessage().contains("workspaceRef 충돌")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
            }
            throw e;
        } catch (RuntimeException e) {
            documentEditService.abandon(documentEdit);
            throw e;
        }
        Map<String, Object> body = toChatCompletionResponse(response);
        if (documentEdit != null) {
            var result = documentEditService.complete(documentEdit, textOf(response), response.turnId(), false);
            if (result != null) body.put("document", result.toMap());
        }
        return ApiResponse.ok(body);
    }

    private static String textOf(ChatResponse response) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : response.content()) {
            if (block instanceof ContentBlock.Text t && t.text() != null) sb.append(t.text());
        }
        return sb.toString();
    }

    private SseEmitter streamResponse(ChatRequest chatRequest, com.platform.chatdoc.PreparedEdit documentEdit) {
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

            // CR-181: 고치기 요청이면 답 글을 모으고, 중지 여부는 이 요청의 중지 표시로 본다. 표시는 첫 이벤트 전에 걸린다.
            final StringBuilder answerText = documentEdit != null ? new StringBuilder() : null;
            final java.util.concurrent.atomic.AtomicReference<java.util.concurrent.atomic.AtomicBoolean> cancelToken =
                    new java.util.concurrent.atomic.AtomicReference<>();

            // 단일 SSE 송출 람다 — orchestrator.chatStream과 SubagentRunner가 공유.
            // CR-053 Phase 2: SubagentStart/Done도 같은 emitter로 송출한다.
            java.util.function.Consumer<com.platform.orchestrator.stream.StreamEvent> sseSink = ev -> {
                if (documentEdit != null && cancelToken.get() == null) {
                    cancelToken.compareAndSet(null, cancellationRegistry.getToken(chatRequest.sessionId()));
                }
                try {
                    switch (ev) {
                        case com.platform.orchestrator.stream.StreamEvent.TextDelta t -> {
                            if (answerText != null && t.delta() != null) answerText.append(t.delta());
                            emitter.send(SseEmitter.event()
                                    .name("delta")
                                    .data(Map.of("delta", t.delta() != null ? t.delta() : "")));
                        }
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
                            // CR-175: 답 번호, 묶음 첫 순번, 답한 모델. 저장에 실패하면 순번이 없어 위젯이 다시 만들기를 숨긴다.
                            if (d.turnId() != null) donePayload.put("turn_id", d.turnId());
                            if (d.turnSeq() != null) donePayload.put("turn_seq", d.turnSeq());
                            if (d.model() != null) donePayload.put("model", d.model());
                            // CR-132: CLI 가 out/ 에 만든 산출물을 done 에 실어 위젯이 다운로드 칩을 렌더한다.
                            // VT 에 TenantContext 전파돼 있어 getWorkspaceRef 가 테넌트 DB 로 라우팅됨.
                            List<Map<String, Object>> artifacts = scanArtifacts(chatRequest.sessionId());
                            if (!artifacts.isEmpty()) {
                                donePayload.put("artifacts", artifacts);
                            }
                            // CR-181: 고친 결과는 끝 이벤트 바로 앞에 별도 이벤트로 보내고 끝 이벤트에도 싣는다.
                            // 위젯 답 아래 카드는 끝 이벤트 칸(AnswerView.extra)으로 그린다.
                            if (documentEdit != null) {
                                java.util.concurrent.atomic.AtomicBoolean token = cancelToken.get();
                                boolean stopped = token != null && token.get();
                                var result = documentEditService.complete(documentEdit,
                                        answerText.toString(), d.turnId(), stopped);
                                if (result != null) {
                                    emitter.send(SseEmitter.event().name("document").data(result.toMap()));
                                    donePayload.put("document", result.toMap());
                                }
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
                documentEditService.abandon(documentEdit);
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
        if (response.turnId() != null) result.put("turn_id", response.turnId());
        if (response.turnSeq() != null) result.put("turn_seq", response.turnSeq());
        // CR-132: 비스트리밍 경로도 산출물 목록을 실어 소비앱/위젯이 다운로드할 수 있게 한다.
        List<Map<String, Object>> artifacts = scanArtifacts(response.sessionId());
        if (!artifacts.isEmpty()) {
            result.put("artifacts", artifacts);
        }
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
            @JsonProperty("working_directory") String workingDirectory,
            // CR-128(갭B): CLI 어댑터 요청 단위 오버라이드. null 이면 커넥션 config 값 사용(기존 동작).
            @JsonProperty("tool_mode") String toolMode,
            @JsonProperty("system_prompt_override") String systemPromptOverride,
            // CR-175: 이 순번(끝 이벤트의 turn_seq)부터 뒤를 지우고 이번 질문으로 다시 답한다(BIZ-148)
            @JsonProperty("rewind_from_seq") Integer rewindFromSeq,
            // CR-175: on, off. 비우면 연결 설정 그대로(BIZ-150)
            String thinking,
            // CR-184: 있으면 그림 연결로 그림 한 장을 만든다(PRD-540, PRD-541)
            ImageDto image,
            // CR-178: 세션을 처음 만들 때 넣을 위젯 프로젝트. 이미 있는 세션이면 쓰지 않는다(PRD-424)
            @JsonProperty("chat_project_id") String chatProjectId,
            // CR-183: 위젯이 그릴 수 있는 형식(chart, math). 값마다 widget.render.<값> 틀을 붙인다(BIZ-223)
            @JsonProperty("render_features") List<String> renderFeatures,
            // CR-182: 파일 만들기 도구를 준다. 런타임 설정이 꺼져 있으면 무시한다(BIZ-210)
            @JsonProperty("file_create") Boolean fileCreate,
            // CR-181: 옆 창 문서 고치기(PRD-480). 비우면 평소 대화
            @JsonProperty("document_edit") com.platform.chatdoc.DocumentEditRequest documentEdit
    ) {
        /** CR-184 이전 15칸 호환(그림 없음) */
        public ChatCompletionRequest(String model, String sessionId, List<MessageDto> messages, boolean stream,
                                     boolean actionsEnabled, String connectionId, String connectionGroupId,
                                     ToolFilterDto toolFilter, String toolChoice, ResponseFormatDto responseFormat,
                                     String workingDirectory, String toolMode, String systemPromptOverride,
                                     Integer rewindFromSeq, String thinking) {
            this(model, sessionId, messages, stream, actionsEnabled, connectionId, connectionGroupId, toolFilter,
                    toolChoice, responseFormat, workingDirectory, toolMode, systemPromptOverride, rewindFromSeq,
                    thinking, null, null, null, null, null);
        }

        /** CR-184 다음 16칸 호환(위젯 프로젝트, 그리기 형식 없음) */
        public ChatCompletionRequest(String model, String sessionId, List<MessageDto> messages, boolean stream,
                                     boolean actionsEnabled, String connectionId, String connectionGroupId,
                                     ToolFilterDto toolFilter, String toolChoice, ResponseFormatDto responseFormat,
                                     String workingDirectory, String toolMode, String systemPromptOverride,
                                     Integer rewindFromSeq, String thinking, ImageDto image) {
            this(model, sessionId, messages, stream, actionsEnabled, connectionId, connectionGroupId, toolFilter,
                    toolChoice, responseFormat, workingDirectory, toolMode, systemPromptOverride, rewindFromSeq,
                    thinking, image, null, null, null, null);
        }

        /** CR-183 이전 17칸 호환(그리기 형식 없음) */
        public ChatCompletionRequest(String model, String sessionId, List<MessageDto> messages, boolean stream,
                                     boolean actionsEnabled, String connectionId, String connectionGroupId,
                                     ToolFilterDto toolFilter, String toolChoice, ResponseFormatDto responseFormat,
                                     String workingDirectory, String toolMode, String systemPromptOverride,
                                     Integer rewindFromSeq, String thinking, ImageDto image, String chatProjectId) {
            this(model, sessionId, messages, stream, actionsEnabled, connectionId, connectionGroupId, toolFilter,
                    toolChoice, responseFormat, workingDirectory, toolMode, systemPromptOverride, rewindFromSeq,
                    thinking, image, chatProjectId, null, null, null);
        }

        /** CR-182 이전 18칸 호환(파일 만들기, 문서 고치기 없음) */
        public ChatCompletionRequest(String model, String sessionId, List<MessageDto> messages, boolean stream,
                                     boolean actionsEnabled, String connectionId, String connectionGroupId,
                                     ToolFilterDto toolFilter, String toolChoice, ResponseFormatDto responseFormat,
                                     String workingDirectory, String toolMode, String systemPromptOverride,
                                     Integer rewindFromSeq, String thinking, ImageDto image, String chatProjectId,
                                     List<String> renderFeatures) {
            this(model, sessionId, messages, stream, actionsEnabled, connectionId, connectionGroupId, toolFilter,
                    toolChoice, responseFormat, workingDirectory, toolMode, systemPromptOverride, rewindFromSeq,
                    thinking, image, chatProjectId, renderFeatures, null, null);
        }

        /** CR-181 이전 19칸 호환(문서 고치기 없음) */
        public ChatCompletionRequest(String model, String sessionId, List<MessageDto> messages, boolean stream,
                                     boolean actionsEnabled, String connectionId, String connectionGroupId,
                                     ToolFilterDto toolFilter, String toolChoice, ResponseFormatDto responseFormat,
                                     String workingDirectory, String toolMode, String systemPromptOverride,
                                     Integer rewindFromSeq, String thinking, ImageDto image, String chatProjectId,
                                     List<String> renderFeatures, Boolean fileCreate) {
            this(model, sessionId, messages, stream, actionsEnabled, connectionId, connectionGroupId, toolFilter,
                    toolChoice, responseFormat, workingDirectory, toolMode, systemPromptOverride, rewindFromSeq,
                    thinking, image, chatProjectId, renderFeatures, fileCreate, null);
        }
    }

    /** CR-184: 그림 요청. action 은 create, edit */
    public record ImageDto(
            String action,
            @JsonProperty("source_image_id") String sourceImageId
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

    // ─── CR-178: 위젯 프로젝트와 이어보기 화면용 질문 ───

    /** 이미 있는 세션이면 null(옮기기는 PATCH). 없거나 요청자의 프로젝트가 아니면 404 project_not_found. */
    private UUID resolveChatProject(ChatCompletionRequest request, String owner) {
        if (request.chatProjectId() == null || request.chatProjectId().isBlank()) return null;
        if (request.sessionId() == null || request.sessionId().isBlank()) return null;
        if (sessionRepository.findBySessionIdIncludingDeleted(request.sessionId()).isPresent()) return null;
        return chatProjectService.requireOwned(owner, request.chatProjectId()).getId();
    }

    /**
     * 이어보기 화면에 보일 질문 글과 첨부. 저장된 질문 블록에는 첨부 번호가 없고 서버가 덧붙인 안내 글이 섞여 있어
     * 사용자가 보낸 그대로를 따로 남긴다(BIZ-174).
     */
    private Map<String, Object> questionDisplay(ChatCompletionRequest request) {
        if (request.sessionId() == null || request.sessionId().isBlank()) return null;
        MessageDto question = null;
        for (MessageDto dto : request.messages()) {
            if ("user".equalsIgnoreCase(dto.role())) question = dto;
        }
        if (question == null) return null;
        StringBuilder text = new StringBuilder();
        List<Map<String, Object>> attachments = new ArrayList<>();
        if (question.content() instanceof String str) {
            text.append(str);
        } else if (question.content() instanceof List<?> parts) {
            for (Object part : parts) {
                if (!(part instanceof Map<?, ?> map)) continue;
                Object type = map.get("type");
                if ("text".equals(type) && map.get("text") instanceof String t) {
                    if (!text.isEmpty()) text.append('\n');
                    text.append(t);
                } else if (("image".equals(type) || "document".equals(type))
                        && map.get("attachment_id") instanceof String id && !id.isBlank()) {
                    ChatAttachmentEntity att = attachmentService.loadOwned(UUID.fromString(id), request.sessionId());
                    Map<String, Object> a = new java.util.LinkedHashMap<>();
                    a.put("attachment_id", att.getId().toString());
                    a.put("filename", att.getFilename());
                    a.put("media_type", att.getMediaType());
                    attachments.add(a);
                }
            }
        }
        Map<String, Object> display = new java.util.LinkedHashMap<>();
        display.put("text", text.toString());
        display.put("attachments", attachments);
        return display;
    }

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
    private UnifiedMessage toUnifiedMessage(MessageDto dto, String sessionId, AdapterResolution adapter,
                                            boolean dataGuide) {
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
                    resolveDocumentBlock(map, sessionId, adapter, blocks, pdfFallbackPrefix, dataGuide);

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
                                       List<ContentBlock> blocks, StringBuilder pdfFallbackPrefix,
                                       boolean dataGuide) {
        String attachmentId = (String) map.get("attachment_id");
        if (attachmentId == null || attachmentId.isBlank()) {
            throw new AttachmentException(HttpStatus.BAD_REQUEST,
                    AttachmentException.CODE_NOT_FOUND,
                    "document block requires attachment_id");
        }
        ChatAttachmentEntity att = attachmentService.loadOwned(UUID.fromString(attachmentId), sessionId);
        byte[] bytes = attachmentService.readBytes(att);

        // CR-183: 워드, 텍스트는 본문 글 블록, 데이터 파일은 요약 글 블록 하나로 보낸다(BIZ-221).
        java.util.Optional<com.platform.attachment.AttachmentKind> kind =
                com.platform.attachment.AttachmentKind.fromMediaType(att.getMediaType());
        if (kind.isPresent()) {
            blocks.add(new ContentBlock.Text(kind.get().isData()
                    ? attachmentPromptBuilder.dataSummary(att, bytes, kind.get(), dataGuide)
                    : attachmentPromptBuilder.documentText(att, bytes, kind.get())));
            return;
        }

        // 그 밖의 document 블록은 PDF 다.
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

    /**
     * CR-061: 모델 문자열 → 어댑터 + capability.
     * CR-175: 연결이 있으면 그 연결 어댑터의 능력으로 정한다(실제로 답할 어댑터). 연결이 없고 모델도
     * 미지정("auto")이면 런타임 설정 llm.default-model 로 정한다.
     */
    private AdapterResolution resolveAdapter(String model, String connectionId) {
        if (connectionId != null && !connectionId.isBlank()) {
            return new AdapterResolution(
                    connectionAdapterFactory.resolveModel(connectionId, model),
                    connectionAdapterFactory.getAdapter(connectionId).capabilities());
        }
        String modelId = model != null && !model.isBlank() && !"auto".equalsIgnoreCase(model)
                ? model : connectionAdapterFactory.platformDefaultModel();
        if (!adapterRegistry.hasAdapter(modelId)) {
            // 등록되지 않은 모델이면 image/pdf 미지원으로 취급 — orchestrator 레이어에서 라우팅 재해석.
            return new AdapterResolution(modelId, com.platform.llm.adapter.AdapterCapability.NONE);
        }
        LLMAdapter adapter = adapterRegistry.getAdapter(modelId);
        return new AdapterResolution(modelId, adapter.capabilities());
    }

    private record AdapterResolution(String modelId, com.platform.llm.adapter.AdapterCapability capability) {}

    /** CR-175 BIZ-150: 연결 config 에 models 가 있으면 그 목록 밖의 모델은 받지 않는다. */
    private void validateModel(ChatCompletionRequest request) {
        if (request.connectionId() == null || request.connectionId().isBlank()) return;
        if (!connectionAdapterFactory.isModelAllowed(request.connectionId(), request.model())) {
            throw new ChatRejectedException(HttpStatus.BAD_REQUEST, "model_not_allowed");
        }
    }

    /** CR-183 BIZ-223: 영문 소문자, 숫자, 하이픈만 최대 10개. 겹친 값은 하나로 줄인다. */
    static List<String> parseRenderFeatures(List<String> raw) {
        if (raw == null || raw.isEmpty()) return null;
        if (raw.size() > MAX_RENDER_FEATURES) {
            throw new ChatRejectedException(HttpStatus.BAD_REQUEST, "render_features_invalid");
        }
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String v : raw) {
            if (v == null || !RENDER_FEATURE.matcher(v).matches()) {
                throw new ChatRejectedException(HttpStatus.BAD_REQUEST, "render_features_invalid");
            }
            out.add(v);
        }
        return List.copyOf(out);
    }

    /** CR-175 BIZ-150: thinking 칸. 켜기를 요청했는데 그 연결 어댑터가 요청마다 바꾸지 못하면 400. */
    private Boolean parseThinking(ChatCompletionRequest request) {
        String thinking = request.thinking();
        if (thinking == null || thinking.isBlank()) return null;
        Boolean on = switch (thinking.toLowerCase(java.util.Locale.ROOT)) {
            case "on" -> Boolean.TRUE;
            case "off" -> Boolean.FALSE;
            default -> throw new ChatRejectedException(HttpStatus.BAD_REQUEST, "thinking_invalid");
        };
        if (on && !widgetSettings.thinkingSupported(request.connectionId(), request.model())) {
            throw new ChatRejectedException(HttpStatus.BAD_REQUEST, "thinking_not_supported");
        }
        return on;
    }

    /** CR-175 BIZ-148: rewind_from_seq 가 오면 그 순번부터 지우고 답한다. */
    // ─── CR-184 그림 만들기 ───

    /** 스트림을 기다리는 여유. 공급처 시간 한도가 지난 뒤 저장과 끝 이벤트를 보낼 시간이다. */
    private static final long IMAGE_STREAM_GRACE_MS = 60_000L;

    private Object imageCompletion(ChatCompletionRequest request) {
        String sessionId = request.sessionId() != null && !request.sessionId().isBlank()
                ? request.sessionId() : UUID.randomUUID().toString();
        List<com.platform.image.ImageGenerationService.RequestMessage> messages = request.messages().stream()
                .map(m -> new com.platform.image.ImageGenerationService.RequestMessage(m.role(), m.content()))
                .toList();
        com.platform.image.ImageGenerationService.Prepared prepared = imageGeneration.prepare(
                request.connectionId(), sessionId, request.image().action(), request.image().sourceImageId(),
                messages);
        rewindIfRequested(request);

        if (request.stream()) {
            return streamImage(prepared);
        }
        try {
            return ApiResponse.ok(imageResponse(imageGeneration.generate(prepared), sessionId));
        } catch (com.platform.image.ImageProviderException e) {
            throw new ChatRejectedException(HttpStatus.BAD_GATEWAY, e.getCode());
        }
    }

    private SseEmitter streamImage(com.platform.image.ImageGenerationService.Prepared prepared) {
        SseEmitter emitter = new SseEmitter(imageSettings.timeoutMs() + IMAGE_STREAM_GRACE_MS);
        final String propagatedTenantId = com.platform.tenant.TenantContext.getTenantId();
        final org.springframework.security.core.context.SecurityContext propagatedSecurityContext =
                org.springframework.security.core.context.SecurityContextHolder.getContext();
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
            try {
                emitter.send(SseEmitter.event().name("image_status").data(Map.of("state", "generating")));
                com.platform.image.ImageGenerationService.Outcome outcome = imageGeneration.generate(prepared);
                if (!outcome.aborted()) {
                    emitter.send(SseEmitter.event().name("delta").data(Map.of("delta", outcome.markdown())));
                }
                emitter.send(SseEmitter.event().name("done").data(imageDone(outcome, prepared.sessionId())));
                emitter.complete();
            } catch (com.platform.image.ImageProviderException e) {
                sendImageError(emitter, e.getCode());
            } catch (IOException e) {
                emitter.completeWithError(e);
            } catch (RuntimeException e) {
                log.warn("Image stream failed: session={} {}", prepared.sessionId(), e.toString());
                sendImageError(emitter, com.platform.image.ImageProviderException.FAILED);
            } finally {
                com.platform.tenant.TenantContext.clear();
            }
        });
        return emitter;
    }

    private static void sendImageError(SseEmitter emitter, String code) {
        try {
            emitter.send(SseEmitter.event().name("error").data(Map.of("code", code)));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private static Map<String, Object> imageDone(com.platform.image.ImageGenerationService.Outcome outcome,
                                                 String sessionId) {
        Map<String, Object> done = new HashMap<>();
        done.put("done", true);
        done.put("session_id", sessionId);
        if (outcome.turnId() != null) done.put("turn_id", outcome.turnId());
        if (outcome.turnSeq() != null) done.put("turn_seq", outcome.turnSeq());
        if (outcome.model() != null) done.put("model", outcome.model());
        done.put("images", imageItems(outcome));
        return done;
    }

    private static Map<String, Object> imageResponse(com.platform.image.ImageGenerationService.Outcome outcome,
                                                     String sessionId) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", outcome.model());
        body.put("session_id", sessionId);
        String text = outcome.aborted() ? "" : outcome.markdown();
        body.put("content", List.of(Map.of("type", "text", "text", text)));
        body.put("images", imageItems(outcome));
        if (outcome.turnId() != null) body.put("turn_id", outcome.turnId());
        if (outcome.turnSeq() != null) body.put("turn_seq", outcome.turnSeq());
        return body;
    }

    private static List<Map<String, Object>> imageItems(com.platform.image.ImageGenerationService.Outcome outcome) {
        if (outcome.image() == null) return List.of();
        Map<String, Object> item = new HashMap<>();
        item.put("id", outcome.image().getId());
        item.put("media_type", outcome.image().getMediaType());
        if (outcome.image().getWidth() != null) item.put("width", outcome.image().getWidth());
        if (outcome.image().getHeight() != null) item.put("height", outcome.image().getHeight());
        return List.of(item);
    }

    private void rewindIfRequested(ChatCompletionRequest request) {
        if (request.rewindFromSeq() == null) return;
        if (request.sessionId() == null || request.sessionId().isBlank()) {
            throw new ChatRejectedException(HttpStatus.BAD_REQUEST, "rewind_invalid:no_session");
        }
        try {
            rewindService.rewind(request.sessionId(), request.rewindFromSeq(),
                    request.connectionId(), request.connectionGroupId());
        } catch (com.platform.session.RewindRejectedException e) {
            throw switch (e.getReason()) {
                case SESSION_BUSY -> new ChatRejectedException(HttpStatus.CONFLICT, "session_busy");
                case NOT_TURN_START -> new ChatRejectedException(HttpStatus.BAD_REQUEST, "rewind_invalid:not_turn_start");
                case BEFORE_COMPACTION -> new ChatRejectedException(HttpStatus.CONFLICT, "rewind_before_compaction");
            };
        }
    }
}
