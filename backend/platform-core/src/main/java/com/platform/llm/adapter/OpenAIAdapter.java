package com.platform.llm.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.models.ChatModel;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.ResponseFormatJsonObject;
import com.openai.models.ResponseFormatJsonSchema;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionNamedToolChoice;
import com.openai.models.chat.completions.ChatCompletionToolChoiceOption;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionContentPart;
import com.openai.models.chat.completions.ChatCompletionContentPartImage;
import com.openai.models.chat.completions.ChatCompletionContentPartText;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.LLMRequest;
import com.platform.llm.model.LLMResponse;
import com.platform.llm.model.LLMStreamChunk;
import com.platform.llm.model.TokenUsage;
import com.platform.llm.model.ToolCall;
import com.platform.llm.model.UnifiedMessage;
import com.platform.tool.model.UnifiedToolDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Component
public class OpenAIAdapter implements LLMAdapter {

    private static final Logger log = LoggerFactory.getLogger(OpenAIAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OpenAIClient client;

    private static final Map<String, double[]> COSTS = Map.of(
            "gpt-4o",      new double[]{2.5,  10.0},
            "gpt-4o-mini", new double[]{0.15,  0.6},
            "gpt-4-turbo", new double[]{10.0, 30.0}
    );

    public OpenAIAdapter(OpenAIClient client) {
        this.client = client;
    }

    @Override
    public String getProvider() { return "openai"; }

    @Override
    public List<String> getSupportedModels() {
        return List.of("gpt-4o", "gpt-4o-mini", "gpt-4-turbo");
    }

    @Override
    public CompletableFuture<LLMResponse> chat(LLMRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            long start = Instant.now().toEpochMilli();
            String modelId = extractModelId(request.model());

            ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                    .model(ChatModel.of(modelId));

            List<ChatCompletionMessageParam> messages = buildOpenAIMessages(request.messages());
            builder.messages(messages);

            if (request.config() != null && request.config().temperature() != null) {
                builder.temperature(request.config().temperature());
            }
            if (request.config() != null && request.config().maxTokens() != null) {
                builder.maxTokens(request.config().maxTokens());
            }

            // CR-007: 구조화 출력 — response_format: json_schema (OpenAI native)
            if (request.responseSchema() != null && !request.responseSchema().isEmpty()) {
                try {
                    String schemaName = request.responseSchema()
                            .getOrDefault("title", "structured_output").toString();
                    // Schema의 각 속성을 JsonValue로 변환
                    Map<String, JsonValue> schemaProps = request.responseSchema().entrySet().stream()
                            .collect(Collectors.toMap(Map.Entry::getKey, e -> JsonValue.from(e.getValue())));

                    builder.responseFormat(ResponseFormatJsonSchema.builder()
                            .jsonSchema(ResponseFormatJsonSchema.JsonSchema.builder()
                                    .name(schemaName)
                                    .schema(ResponseFormatJsonSchema.JsonSchema.Schema.builder()
                                            .putAllAdditionalProperties(schemaProps)
                                            .build())
                                    .strict(true)
                                    .build())
                            .build());
                } catch (Exception e) {
                    log.warn("Failed to set response_format json_schema, falling back to json_object: {}", e.getMessage());
                    builder.responseFormat(ResponseFormatJsonObject.builder().build());
                }
            }

            if (request.tools() != null && !request.tools().isEmpty()) {
                @SuppressWarnings("unchecked")
                List<ChatCompletionTool> openAiTools = (List<ChatCompletionTool>) transformToolDefs(request.tools());
                builder.tools(openAiTools);

                // CR-006: tool_choice 매핑
                if (request.toolChoice() != null) {
                    ChatCompletionToolChoiceOption choice = mapToolChoice(request.toolChoice());
                    if (choice != null) {
                        builder.toolChoice(choice);
                    }
                }
            }

            ChatCompletion response = client.chat().completions().create(builder.build());
            long latencyMs = Instant.now().toEpochMilli() - start;
            return toLLMResponse(response, request.model(), latencyMs);
        });
    }

    @Override
    public void chatStream(LLMRequest request, Consumer<LLMStreamChunk> chunkConsumer) {
        Thread.ofVirtual().start(() -> {
            long start = Instant.now().toEpochMilli();
            String modelId = extractModelId(request.model());
            String responseId = "chatcmpl_" + System.currentTimeMillis();

            ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                    .model(ChatModel.of(modelId));

            builder.messages(buildOpenAIMessages(request.messages()));

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
                log.error("OpenAI streaming error", e);
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

    // ─── Private Helpers ────────────────────────────────────────────────

    /**
     * CR-006: toolChoice 문자열을 OpenAI ChatCompletionToolChoiceOption으로 매핑.
     * - "auto" → ofAuto("auto")
     * - "none" → ofAuto("none")
     * - "required" → ofAuto("required")
     * - 그 외 → ofChatCompletionNamedToolChoice (특정 function 강제)
     */
    private ChatCompletionToolChoiceOption mapToolChoice(String toolChoice) {
        return switch (toolChoice) {
            case "auto", "none", "required" ->
                    ChatCompletionToolChoiceOption.ofAuto(
                            ChatCompletionToolChoiceOption.Auto.of(toolChoice));
            default ->
                    ChatCompletionToolChoiceOption.ofNamedToolChoice(
                            ChatCompletionNamedToolChoice.builder()
                                    .function(ChatCompletionNamedToolChoice.Function.builder()
                                            .name(toolChoice)
                                            .build())
                                    .build());
        };
    }

    private String extractModelId(String model) {
        return model.contains("/") ? model.substring(model.indexOf("/") + 1) : model;
    }

    /**
     * UnifiedMessage 목록을 OpenAI 메시지 목록으로 변환.
     * TOOL_RESULT 메시지는 ToolResult 블록당 하나의 tool 역할 메시지로 확장.
     * (OpenAI는 tool result를 별도 메시지로, Anthropic은 하나의 user 메시지에 묶어서 전달)
     */
    private List<ChatCompletionMessageParam> buildOpenAIMessages(List<UnifiedMessage> messages) {
        List<ChatCompletionMessageParam> result = new ArrayList<>();
        for (UnifiedMessage msg : messages) {
            if (msg.role() == UnifiedMessage.Role.TOOL_RESULT) {
                // 각 ToolResult를 개별 tool 역할 메시지로 확장
                for (com.platform.llm.model.ContentBlock block : msg.content()) {
                    if (block instanceof ContentBlock.ToolResult tr) {
                        result.add(ChatCompletionMessageParam.ofTool(
                                ChatCompletionToolMessageParam.builder()
                                        .toolCallId(tr.toolUseId())
                                        .content(tr.content())
                                        .build()));
                    }
                }
            } else {
                result.add(toOpenAIMessage(msg));
            }
        }
        return result;
    }

    private ChatCompletionMessageParam toOpenAIMessage(UnifiedMessage msg) {
        return switch (msg.role()) {
            case SYSTEM -> ChatCompletionMessageParam.ofSystem(
                    ChatCompletionSystemMessageParam.builder().content(extractText(msg)).build());
            case ASSISTANT -> {
                boolean hasToolUse = msg.content().stream()
                        .anyMatch(b -> b instanceof ContentBlock.ToolUse);
                if (hasToolUse) {
                    List<ChatCompletionMessageToolCall> toolCalls = msg.content().stream()
                            .filter(b -> b instanceof ContentBlock.ToolUse)
                            .map(b -> {
                                ContentBlock.ToolUse tu = (ContentBlock.ToolUse) b;
                                String argsJson;
                                try {
                                    argsJson = MAPPER.writeValueAsString(tu.input());
                                } catch (Exception e) {
                                    argsJson = "{}";
                                }
                                return ChatCompletionMessageToolCall.builder()
                                        .id(tu.id())
                                        .function(ChatCompletionMessageToolCall.Function.builder()
                                                .name(tu.name())
                                                .arguments(argsJson)
                                                .build())
                                        .build();
                            })
                            .toList();
                    yield ChatCompletionMessageParam.ofAssistant(
                            ChatCompletionAssistantMessageParam.builder()
                                    .toolCalls(toolCalls)
                                    .build());
                }
                yield ChatCompletionMessageParam.ofAssistant(
                        ChatCompletionAssistantMessageParam.builder()
                                .content(ChatCompletionAssistantMessageParam.Content.ofText(extractText(msg)))
                                .build());
            }
            default -> {
                // PRD-111: 멀티모달 — Image 블록 포함 시 content part 배열로 변환
                boolean hasImage = msg.content().stream()
                        .anyMatch(b -> b instanceof ContentBlock.Image);
                if (hasImage) {
                    List<ChatCompletionContentPart> parts = msg.content().stream()
                            .map(b -> {
                                if (b instanceof ContentBlock.Image img) {
                                    String imageUrl;
                                    if (img.isBase64()) {
                                        imageUrl = "data:" + img.mediaType() + ";base64," + img.data();
                                    } else {
                                        imageUrl = img.url();
                                    }
                                    return ChatCompletionContentPart.ofImageUrl(
                                            ChatCompletionContentPartImage.builder()
                                                    .imageUrl(ChatCompletionContentPartImage.ImageUrl.builder()
                                                            .url(imageUrl)
                                                            .build())
                                                    .build());
                                } else if (b instanceof ContentBlock.Text text) {
                                    return ChatCompletionContentPart.ofText(
                                            ChatCompletionContentPartText.builder()
                                                    .text(text.text())
                                                    .build());
                                }
                                return ChatCompletionContentPart.ofText(
                                        ChatCompletionContentPartText.builder().text("").build());
                            })
                            .toList();
                    yield ChatCompletionMessageParam.ofUser(
                            ChatCompletionUserMessageParam.builder()
                                    .contentOfArrayOfContentParts(parts)
                                    .build());
                }
                yield ChatCompletionMessageParam.ofUser(
                        ChatCompletionUserMessageParam.builder()
                                .content(ChatCompletionUserMessageParam.Content.ofText(extractText(msg)))
                                .build());
            }
        };
    }

    private String extractText(UnifiedMessage msg) {
        return msg.content().stream()
                .filter(b -> b instanceof ContentBlock.Text)
                .map(b -> ((ContentBlock.Text) b).text())
                .reduce("", (a, s) -> a + s);
    }

    private LLMResponse toLLMResponse(ChatCompletion completion, String modelId, long latencyMs) {
        ChatCompletion.Choice choice = completion.choices().get(0);
        String text = choice.message().content().orElse("");

        List<ContentBlock> content = List.of(new ContentBlock.Text(text));
        List<ToolCall> toolCalls = parseToolCalls(completion);

        long inputTokens = completion.usage().map(u -> u.promptTokens()).orElse(0L);
        long outputTokens = completion.usage().map(u -> u.completionTokens()).orElse(0L);
        TokenUsage usage = new TokenUsage((int) inputTokens, (int) outputTokens);

        String model = extractModelId(modelId);
        double[] costPerMillion = COSTS.getOrDefault(model, new double[]{2.5, 10.0});
        double cost = (inputTokens * costPerMillion[0] + outputTokens * costPerMillion[1]) / 1_000_000;

        LLMResponse.FinishReason finishReason = switch (choice.finishReason().asString()) {
            case "tool_calls" -> LLMResponse.FinishReason.TOOL_USE;
            case "length"     -> LLMResponse.FinishReason.MAX_TOKENS;
            default           -> LLMResponse.FinishReason.END;
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
