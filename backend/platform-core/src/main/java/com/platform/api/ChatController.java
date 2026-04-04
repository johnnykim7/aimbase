package com.platform.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.UnifiedMessage;
import com.platform.orchestrator.ChatRequest;
import com.platform.orchestrator.ChatResponse;
import com.platform.orchestrator.OrchestratorEngine;
import com.platform.tool.ToolFilterContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/chat")
@Tag(name = "Chat", description = "LLM 채팅 API")
public class ChatController {

    private final OrchestratorEngine orchestrator;

    public ChatController(OrchestratorEngine orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/completions")
    @Operation(summary = "채팅 완성 요청", description = "LLM 모델에 메시지를 전송하고 응답을 받는다. stream=true이면 SSE로 응답.")
    public Object completions(@Valid @RequestBody ChatCompletionRequest request) {
        List<UnifiedMessage> messages = request.messages().stream()
                .map(this::toUnifiedMessage)
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
                request.connectionGroupId()
        );

        if (request.stream()) {
            return streamResponse(chatRequest);
        }
        ChatResponse response = orchestrator.chat(chatRequest);
        return ApiResponse.ok(toChatCompletionResponse(response));
    }

    private SseEmitter streamResponse(ChatRequest chatRequest) {
        SseEmitter emitter = new SseEmitter(120_000L);
        Thread.ofVirtual().start(() -> {
            orchestrator.chatStream(chatRequest, chunk -> {
                try {
                    if (chunk.done()) {
                        emitter.send(SseEmitter.event()
                                .name("done")
                                .data(Map.of("done", true)));
                        emitter.complete();
                    } else {
                        emitter.send(SseEmitter.event()
                                .name("delta")
                                .data(Map.of("delta", chunk.delta() != null ? chunk.delta() : "")));
                    }
                } catch (IOException e) {
                    emitter.completeWithError(e);
                }
            });
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
        result.put("usage", Map.of(
                "input_tokens", response.usage().inputTokens(),
                "output_tokens", response.usage().outputTokens(),
                "cost_usd", response.costUsd()
        ));
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
            @JsonProperty("response_format") ResponseFormatDto responseFormat
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

    // ─── 멀티모달 변환 헬퍼 ───

    @SuppressWarnings("unchecked")
    private UnifiedMessage toUnifiedMessage(MessageDto dto) {
        UnifiedMessage.Role role = UnifiedMessage.Role.valueOf(dto.role().toUpperCase());

        if (dto.content() instanceof String text) {
            // 기존 동작: 문자열 content
            return UnifiedMessage.ofText(role, text);
        }

        if (dto.content() instanceof List<?> parts) {
            // 멀티모달: List<Map> → ContentBlock 목록
            List<ContentBlock> blocks = new ArrayList<>();
            for (Object part : parts) {
                if (part instanceof Map<?, ?> map) {
                    String type = (String) map.get("type");
                    if ("text".equals(type)) {
                        String text = (String) map.get("text");
                        if (text != null) blocks.add(new ContentBlock.Text(text));
                    } else if ("image_url".equals(type)) {
                        Map<?, ?> imageUrl = (Map<?, ?>) map.get("image_url");
                        if (imageUrl != null) {
                            String url = (String) imageUrl.get("url");
                            if (url != null && url.startsWith("data:")) {
                                // data URI: data:image/png;base64,<base64data>
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
            }
            if (blocks.isEmpty()) {
                return UnifiedMessage.ofText(role, "");
            }
            return new UnifiedMessage(role, blocks);
        }

        // fallback
        return UnifiedMessage.ofText(role, dto.content() != null ? dto.content().toString() : "");
    }
}
