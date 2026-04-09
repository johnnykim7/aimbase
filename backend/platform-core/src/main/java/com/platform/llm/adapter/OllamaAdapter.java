package com.platform.llm.adapter;

import com.platform.tool.model.UnifiedToolDef;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.llm.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Ollama/vLLM 어댑터: OpenAI 호환 REST API를 사용하므로 endpoint URL만 다름.
 * 로컬 LLM은 비용이 0이므로 costUsd = 0.0
 */
@Component
public class OllamaAdapter implements LLMAdapter {

    private static final Logger log = LoggerFactory.getLogger(OllamaAdapter.class);

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OllamaAdapter(
            @Value("${ollama.base-url:http://localhost:11434}") String baseUrl,
            ObjectMapper objectMapper
    ) {
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = objectMapper;
    }

    @Override
    public String getProvider() {
        return "ollama";
    }

    @Override
    public List<String> getSupportedModels() {
        // 동적으로 /api/tags 조회 가능 — Phase 2에서 확장
        return List.of("llama3.2", "mistral", "gemma2", "phi3");
    }

    @Override
    public CompletableFuture<LLMResponse> chat(LLMRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            long start = Instant.now().toEpochMilli();
            String modelId = extractModelId(request.model());

            // CR-007: 구조화 출력 시 system prompt에 스키마 주입
            boolean structuredMode = request.responseSchema() != null && !request.responseSchema().isEmpty();
            List<Map<String, Object>> messages = new ArrayList<>(request.messages().stream()
                    .map(this::toOllamaMessage)
                    .toList());

            if (structuredMode) {
                try {
                    String schemaJson = objectMapper.writeValueAsString(request.responseSchema());
                    String schemaPrompt = "[STRUCTURED OUTPUT]\nYou MUST respond with valid JSON matching this schema:\n"
                            + schemaJson + "\nReturn ONLY the JSON object, no other text.";
                    messages.add(0, Map.of("role", "system", "content", schemaPrompt));
                } catch (Exception e) {
                    log.warn("Failed to inject schema prompt for Ollama: {}", e.getMessage());
                }
            }

            // CR-006: toolChoice가 "none"이면 도구를 요청에 포함하지 않음
            boolean excludeTools = "none".equals(request.toolChoice());
            if (request.toolChoice() != null && !"auto".equals(request.toolChoice()) && !excludeTools) {
                log.warn("Ollama does not support tool_choice='{}', falling back to auto", request.toolChoice());
            }

            // CR-007: 구조화 모드에서 response_format: json_object 추가
            Map<String, Object> body = new java.util.HashMap<>(Map.of(
                    "model", modelId,
                    "messages", messages,
                    "stream", false
            ));
            // CR-006: toolChoice가 "none"이 아닐 때만 도구 포함
            if (!excludeTools && request.tools() != null && !request.tools().isEmpty()) {
                body.put("tools", transformToolDefs(request.tools()));
            }
            if (structuredMode) {
                body.put("response_format", Map.of("type", "json_object"));
            }

            try {
                String json = objectMapper.writeValueAsString(body);
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/v1/chat/completions"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();

                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                long latencyMs = Instant.now().toEpochMilli() - start;

                return parseOpenAIResponse(resp.body(), request.model(), latencyMs);
            } catch (Exception e) {
                throw new RuntimeException("Ollama chat error: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public void chatStream(LLMRequest request, Consumer<LLMStreamChunk> chunkConsumer) {
        Thread.ofVirtual().start(() -> {
            String modelId = extractModelId(request.model());
            String responseId = "ollama_" + System.currentTimeMillis();

            List<Map<String, Object>> messages = request.messages().stream()
                    .map(msg -> Map.<String, Object>of(
                            "role", toOpenAIRole(msg.role()),
                            "content", extractText(msg)
                    ))
                    .toList();

            Map<String, Object> body = Map.of(
                    "model", modelId,
                    "messages", messages,
                    "stream", true
            );

            try {
                String json = objectMapper.writeValueAsString(body);
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/v1/chat/completions"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();

                httpClient.send(req, HttpResponse.BodyHandlers.ofLines()).body().forEach(line -> {
                    if (line.startsWith("data: ") && !line.equals("data: [DONE]")) {
                        try {
                            String data = line.substring(6);
                            Map<?, ?> chunk = objectMapper.readValue(data, Map.class);
                            List<?> choices = (List<?>) chunk.get("choices");
                            if (choices != null && !choices.isEmpty()) {
                                Map<?, ?> delta = (Map<?, ?>) ((Map<?, ?>) choices.get(0)).get("delta");
                                if (delta != null && delta.get("content") != null) {
                                    String text = (String) delta.get("content");
                                    chunkConsumer.accept(LLMStreamChunk.text(responseId, request.model(), text));
                                }
                            }
                        } catch (Exception e) {
                            log.warn("Failed to parse stream chunk: {}", line);
                        }
                    }
                });
                chunkConsumer.accept(LLMStreamChunk.done(responseId, request.model(), null));
            } catch (Exception e) {
                log.error("Ollama streaming error", e);
                chunkConsumer.accept(new LLMStreamChunk("error", request.model(), null, true, null));
            }
        });
    }

    @Override
    public Object transformToolDefs(List<UnifiedToolDef> tools) {
        return tools.stream().map(t -> Map.of(
                "type", "function",
                "function", Map.of(
                        "name", t.name(),
                        "description", t.description(),
                        "parameters", t.inputSchema()
                )
        )).toList();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<ToolCall> parseToolCalls(Object nativeResponse) {
        if (!(nativeResponse instanceof Map<?, ?> resp)) return List.of();
        List<?> choices = (List<?>) resp.get("choices");
        if (choices == null || choices.isEmpty()) return List.of();
        Map<?, ?> message = (Map<?, ?>) ((Map<?, ?>) choices.get(0)).get("message");
        if (message == null) return List.of();
        List<?> toolCalls = (List<?>) message.get("tool_calls");
        if (toolCalls == null) return List.of();
        return toolCalls.stream().map(tc -> {
            Map<?, ?> call = (Map<?, ?>) tc;
            Map<?, ?> function = (Map<?, ?>) call.get("function");
            String argsStr = (String) function.get("arguments");
            Map<String, Object> args;
            try {
                args = objectMapper.readValue(argsStr, Map.class);
            } catch (Exception e) {
                args = Map.of("raw", argsStr);
            }
            return new ToolCall((String) call.get("id"), (String) function.get("name"), args);
        }).toList();
    }

    private String extractModelId(String model) {
        if (model.contains("/")) {
            return model.substring(model.indexOf("/") + 1);
        }
        return model;
    }

    private String toOpenAIRole(UnifiedMessage.Role role) {
        return switch (role) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case TOOL_RESULT -> "tool";
        };
    }

    /**
     * PRD-111: UnifiedMessage → Ollama 메시지 맵 변환.
     * Image 블록이 있으면 images 필드에 base64 배열로 추가 (Ollama native multimodal).
     */
    private Map<String, Object> toOllamaMessage(UnifiedMessage msg) {
        String text = extractText(msg);
        List<String> images = msg.content().stream()
                .filter(b -> b instanceof ContentBlock.Image)
                .map(b -> {
                    ContentBlock.Image img = (ContentBlock.Image) b;
                    if (img.isBase64()) {
                        return img.data();
                    } else if (img.isUrl()) {
                        // Ollama는 URL을 직접 지원하지 않으므로 URL을 그대로 전달 (일부 모델은 지원)
                        return img.url();
                    }
                    return null;
                })
                .filter(s -> s != null)
                .toList();

        if (!images.isEmpty()) {
            Map<String, Object> message = new java.util.HashMap<>();
            message.put("role", toOpenAIRole(msg.role()));
            message.put("content", text);
            message.put("images", images);
            return message;
        }
        return Map.of("role", toOpenAIRole(msg.role()), "content", text);
    }

    private String extractText(UnifiedMessage msg) {
        return msg.content().stream()
                .filter(b -> b instanceof ContentBlock.Text)
                .map(b -> ((ContentBlock.Text) b).text())
                .reduce("", String::concat);
    }

    @SuppressWarnings("unchecked")
    private LLMResponse parseOpenAIResponse(String body, String modelId, long latencyMs) {
        try {
            Map<String, Object> resp = objectMapper.readValue(body, Map.class);
            String id = (String) resp.getOrDefault("id", "ollama_" + System.currentTimeMillis());
            List<?> choices = (List<?>) resp.get("choices");
            String text = "";
            if (choices != null && !choices.isEmpty()) {
                Map<?, ?> message = (Map<?, ?>) ((Map<?, ?>) choices.get(0)).get("message");
                if (message != null) {
                    Object contentObj = message.get("content");
                    text = contentObj != null ? contentObj.toString() : "";
                }
            }
            Map<?, ?> usage = (Map<?, ?>) resp.get("usage");
            Object promptTokens = usage != null ? usage.get("prompt_tokens") : null;
            int inputTokens = promptTokens != null ? ((Number) promptTokens).intValue() : 0;
            Object completionTokens = usage != null ? usage.get("completion_tokens") : null;
            int outputTokens = completionTokens != null ? ((Number) completionTokens).intValue() : 0;

            return new LLMResponse(
                    id, modelId,
                    List.of(new ContentBlock.Text(text)),
                    List.of(),
                    new TokenUsage(inputTokens, outputTokens),
                    LLMResponse.FinishReason.END,
                    latencyMs,
                    0.0  // 로컬 LLM은 비용 없음
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Ollama response", e);
        }
    }
}
