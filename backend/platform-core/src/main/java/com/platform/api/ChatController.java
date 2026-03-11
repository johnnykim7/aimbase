package com.platform.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.UnifiedMessage;
import com.platform.orchestrator.ChatRequest;
import com.platform.orchestrator.ChatResponse;
import com.platform.orchestrator.OrchestratorEngine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
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
                .map(m -> UnifiedMessage.ofText(
                        UnifiedMessage.Role.valueOf(m.role().toUpperCase()),
                        m.content()
                ))
                .toList();

        ChatRequest chatRequest = new ChatRequest(
                request.model(),
                request.sessionId(),
                messages,
                request.stream(),
                request.actionsEnabled(),
                null,
                null,
                request.connectionId()
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
        String text = response.content().stream()
                .filter(b -> b instanceof ContentBlock.Text)
                .map(b -> ((ContentBlock.Text) b).text())
                .reduce("", String::concat);

        return Map.of(
                "id", response.id(),
                "model", response.model(),
                "session_id", response.sessionId(),
                "content", List.of(Map.of("type", "text", "text", text)),
                "actions_executed", response.actionsExecuted(),
                "usage", Map.of(
                        "input_tokens", response.usage().inputTokens(),
                        "output_tokens", response.usage().outputTokens(),
                        "cost_usd", response.costUsd()
                )
        );
    }

    // ─── Request/Response DTOs ───

    public record ChatCompletionRequest(
            String model,
            @JsonProperty("session_id") String sessionId,
            @NotEmpty List<MessageDto> messages,
            boolean stream,
            @JsonProperty("actions_enabled") boolean actionsEnabled,
            @JsonProperty("connection_id") String connectionId
    ) {}

    public record MessageDto(
            @NotBlank String role,
            @NotBlank String content
    ) {}
}
