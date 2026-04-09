package com.platform.llm.adapter;

import com.platform.tool.model.UnifiedToolDef;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.ChatModel;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.*;
import com.platform.llm.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * CR-032 PRD-217: OpenAI Chat Completions API 호환 범용 어댑터.
 *
 * base_url + api_key + model로 DeepSeek, Groq, Mistral, LM Studio,
 * OpenRouter, Together, Fireworks, vLLM, LocalAI 등에 연결한다.
 *
 * OpenAI SDK의 baseUrl 기능을 활용하여 동일 프로토콜로 200+ 모델 지원.
 */
public class OpenAICompatibleAdapter implements LLMAdapter {

    private static final Logger log = LoggerFactory.getLogger(OpenAICompatibleAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OpenAIClient client;
    private final String defaultModel;
    private final String provider;

    public OpenAICompatibleAdapter(String baseUrl, String apiKey, String defaultModel, String provider) {
        this.client = OpenAIOkHttpClient.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();
        this.defaultModel = defaultModel;
        this.provider = provider != null ? provider : "openai_compatible";
        log.info("OpenAICompatibleAdapter 초기화: baseUrl={}, model={}, provider={}",
                baseUrl, defaultModel, provider);
    }

    @Override
    public String getProvider() { return provider; }

    @Override
    public List<String> getSupportedModels() {
        return List.of(defaultModel);
    }

    @Override
    public CompletableFuture<LLMResponse> chat(LLMRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            long start = Instant.now().toEpochMilli();
            String modelId = resolveModel(request.model());

            ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                    .model(ChatModel.of(modelId));

            builder.messages(buildMessages(request.messages()));

            if (request.config() != null && request.config().temperature() != null) {
                builder.temperature(request.config().temperature());
            }
            if (request.config() != null && request.config().maxTokens() != null) {
                builder.maxTokens(request.config().maxTokens());
            }

            // 도구 정의
            if (request.tools() != null && !request.tools().isEmpty()) {
                @SuppressWarnings("unchecked")
                List<ChatCompletionTool> tools = (List<ChatCompletionTool>) transformToolDefs(request.tools());
                builder.tools(tools);
            }

            try {
                ChatCompletion response = client.chat().completions().create(builder.build());
                long latencyMs = Instant.now().toEpochMilli() - start;
                return toResponse(response, request.model(), latencyMs);
            } catch (Exception e) {
                log.error("OpenAI Compatible chat 실패: provider={}, model={}, error={}",
                        provider, modelId, e.getMessage());
                throw new RuntimeException("OpenAI Compatible API 호출 실패: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public void chatStream(LLMRequest request, Consumer<LLMStreamChunk> chunkConsumer) {
        Thread.ofVirtual().start(() -> {
            String modelId = resolveModel(request.model());
            String responseId = "compat_" + System.currentTimeMillis();

            ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                    .model(ChatModel.of(modelId));
            builder.messages(buildMessages(request.messages()));

            try (var stream = client.chat().completions().createStreaming(builder.build())) {
                stream.stream().forEach(chunk -> {
                    chunk.choices().stream()
                            .filter(c -> c.delta().content().isPresent())
                            .forEach(c -> {
                                String delta = c.delta().content().get();
                                chunkConsumer.accept(LLMStreamChunk.text(responseId, request.model(), delta));
                            });
                });
                chunkConsumer.accept(LLMStreamChunk.done(responseId, request.model(), null));
            } catch (Exception e) {
                log.error("OpenAI Compatible streaming 실패: {}", e.getMessage());
                chunkConsumer.accept(new LLMStreamChunk("error", request.model(), null, true, null));
            }
        });
    }

    @Override
    public Object transformToolDefs(List<UnifiedToolDef> tools) {
        return tools.stream().map(t -> {
            Map<String, JsonValue> jsonParams = t.inputSchema().entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> JsonValue.from(e.getValue())));
            return ChatCompletionTool.builder()
                    .function(FunctionDefinition.builder()
                            .name(t.name())
                            .description(t.description())
                            .parameters(FunctionParameters.builder()
                                    .putAllAdditionalProperties(jsonParams)
                                    .build())
                            .build())
                    .build();
        }).toList();
    }

    @Override
    public List<ToolCall> parseToolCalls(Object nativeResponse) {
        if (!(nativeResponse instanceof ChatCompletion completion)) {
            return List.of();
        }
        return completion.choices().stream()
                .flatMap(choice -> {
                    var calls = choice.message().toolCalls();
                    if (calls.isEmpty()) return java.util.stream.Stream.empty();
                    return calls.get().stream().map(tc -> new ToolCall(
                            tc.id(),
                            tc.function().name(),
                            parseArguments(tc.function().arguments())
                    ));
                })
                .toList();
    }

    // ─── Private Helpers ───

    private String resolveModel(String requestedModel) {
        if (requestedModel != null && !requestedModel.isBlank() && !requestedModel.equals("auto")) {
            return requestedModel.contains("/") ? requestedModel.substring(requestedModel.indexOf("/") + 1) : requestedModel;
        }
        return defaultModel;
    }

    private List<ChatCompletionMessageParam> buildMessages(List<UnifiedMessage> messages) {
        List<ChatCompletionMessageParam> result = new ArrayList<>();
        for (UnifiedMessage msg : messages) {
            if (msg.role() == UnifiedMessage.Role.TOOL_RESULT) {
                for (ContentBlock block : msg.content()) {
                    if (block instanceof ContentBlock.ToolResult tr) {
                        result.add(ChatCompletionMessageParam.ofTool(
                                ChatCompletionToolMessageParam.builder()
                                        .toolCallId(tr.toolUseId())
                                        .content(tr.content())
                                        .build()));
                    }
                }
            } else {
                result.add(toMessage(msg));
            }
        }
        return result;
    }

    private ChatCompletionMessageParam toMessage(UnifiedMessage msg) {
        String text = msg.content().stream()
                .filter(b -> b instanceof ContentBlock.Text)
                .map(b -> ((ContentBlock.Text) b).text())
                .reduce("", (a, s) -> a + s);

        return switch (msg.role()) {
            case SYSTEM -> ChatCompletionMessageParam.ofSystem(
                    ChatCompletionSystemMessageParam.builder().content(text).build());
            case ASSISTANT -> ChatCompletionMessageParam.ofAssistant(
                    ChatCompletionAssistantMessageParam.builder()
                            .content(ChatCompletionAssistantMessageParam.Content.ofText(text))
                            .build());
            default -> ChatCompletionMessageParam.ofUser(
                    ChatCompletionUserMessageParam.builder()
                            .content(ChatCompletionUserMessageParam.Content.ofText(text))
                            .build());
        };
    }

    private LLMResponse toResponse(ChatCompletion completion, String modelId, long latencyMs) {
        ChatCompletion.Choice choice = completion.choices().get(0);
        String text = choice.message().content().orElse("");

        List<ContentBlock> content = List.of(new ContentBlock.Text(text));
        List<ToolCall> toolCalls = parseToolCalls(completion);

        long inputTokens = completion.usage().map(u -> u.promptTokens()).orElse(0L);
        long outputTokens = completion.usage().map(u -> u.completionTokens()).orElse(0L);
        TokenUsage usage = new TokenUsage((int) inputTokens, (int) outputTokens);

        // 범용 shim은 비용 미추정 (프로바이더별 단가 상이)
        double cost = 0.0;

        LLMResponse.FinishReason finishReason = switch (choice.finishReason().asString()) {
            case "tool_calls" -> LLMResponse.FinishReason.TOOL_USE;
            case "length" -> LLMResponse.FinishReason.MAX_TOKENS;
            default -> LLMResponse.FinishReason.END;
        };

        return new LLMResponse(completion.id(), modelId, content, toolCalls, usage, finishReason, latencyMs, cost);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(String json) {
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of("raw", json);
        }
    }
}
