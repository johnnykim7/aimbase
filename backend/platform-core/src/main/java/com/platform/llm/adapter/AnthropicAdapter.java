package com.platform.llm.adapter;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.anthropic.models.messages.ToolChoice;
import com.anthropic.models.messages.ToolChoiceAny;
import com.anthropic.models.messages.ToolChoiceAuto;
import com.anthropic.models.messages.ToolChoiceNone;
import com.anthropic.models.messages.ToolChoiceTool;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.UrlImageSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.llm.model.LLMRequest;
import com.platform.llm.model.LLMResponse;
import com.platform.llm.model.LLMStreamChunk;
import com.platform.llm.model.TokenUsage;
import com.platform.llm.model.ToolCall;
import com.platform.llm.model.UnifiedMessage;
import com.platform.llm.model.UnifiedToolDef;
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
public class AnthropicAdapter implements LLMAdapter {

    private static final Logger log = LoggerFactory.getLogger(AnthropicAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AnthropicClient client;

    private static final Map<String, double[]> COSTS = Map.of(
            "claude-opus-4-6",           new double[]{15.0, 75.0},
            "claude-sonnet-4-5",         new double[]{3.0,  15.0},
            "claude-haiku-4-5-20251001", new double[]{0.25,  1.25}
    );

    public AnthropicAdapter(AnthropicClient client) {
        this.client = client;
    }

    @Override
    public String getProvider() { return "anthropic"; }

    @Override
    public List<String> getSupportedModels() {
        return List.of("claude-opus-4-6", "claude-sonnet-4-5", "claude-haiku-4-5-20251001");
    }

    @Override
    public CompletableFuture<LLMResponse> chat(LLMRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            long start = Instant.now().toEpochMilli();
            String modelId = extractModelId(request.model());

            MessageCreateParams.Builder builder = MessageCreateParams.builder()
                    .model(Model.of(modelId))
                    .maxTokens(request.config() != null && request.config().maxTokens() != null
                            ? request.config().maxTokens() : 4096);

            List<MessageParam> userMessages = new ArrayList<>();
            StringBuilder systemText = new StringBuilder();
            for (UnifiedMessage msg : request.messages()) {
                if (msg.role() == UnifiedMessage.Role.SYSTEM) {
                    systemText.append(extractText(msg));
                } else {
                    userMessages.add(toAnthropicMessage(msg));
                }
            }

            // CR-007: Claude 구조화 출력 — system prompt에 스키마 주입 + Tool Use trick
            boolean structuredMode = request.responseSchema() != null && !request.responseSchema().isEmpty();
            if (structuredMode) {
                try {
                    String schemaJson = MAPPER.writeValueAsString(request.responseSchema());
                    systemText.append("\n\n[STRUCTURED OUTPUT INSTRUCTION]\n")
                            .append("You MUST respond by calling the 'structured_output' tool with data matching this JSON Schema:\n")
                            .append(schemaJson)
                            .append("\nDo NOT return plain text. You MUST call the tool.");
                } catch (Exception e) {
                    log.warn("Failed to serialize schema for Anthropic prompt injection: {}", e.getMessage());
                }
            }
            if (!systemText.isEmpty()) {
                builder.system(systemText.toString());
            }
            builder.messages(userMessages);

            if (request.config() != null && request.config().temperature() != null) {
                builder.temperature(request.config().temperature());
            }

            // CR-007: structured mode → 가상 tool 정의 + tool_choice: any
            if (structuredMode) {
                Map<String, JsonValue> schemaProps = request.responseSchema().entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, e -> JsonValue.from(e.getValue())));
                Tool structuredTool = Tool.builder()
                        .name("structured_output")
                        .description("Return structured data matching the requested JSON Schema")
                        .inputSchema(Tool.InputSchema.builder()
                                .type(JsonValue.from("object"))
                                .putAllAdditionalProperties(schemaProps)
                                .build())
                        .build();
                builder.tools(List.of(ToolUnion.Companion.ofTool(structuredTool)));
                builder.toolChoice(ToolChoice.ofAny(ToolChoiceAny.builder().build()));
            } else if (request.tools() != null && !request.tools().isEmpty()) {
                @SuppressWarnings("unchecked")
                List<Tool> anthropicTools = (List<Tool>) transformToolDefs(request.tools());
                List<ToolUnion> toolUnions = anthropicTools.stream()
                        .map(t -> ToolUnion.Companion.ofTool(t))
                        .toList();
                builder.tools(toolUnions);

                // CR-006: tool_choice 매핑
                if (request.toolChoice() != null) {
                    ToolChoice choice = mapToolChoice(request.toolChoice());
                    if (choice != null) {
                        builder.toolChoice(choice);
                    }
                }
            }

            var response = client.messages().create(builder.build());
            long latencyMs = Instant.now().toEpochMilli() - start;
            return toLLMResponse(response, request.model(), latencyMs);
        });
    }

    @Override
    public void chatStream(LLMRequest request, Consumer<LLMStreamChunk> chunkConsumer) {
        Thread.ofVirtual().start(() -> {
            long start = Instant.now().toEpochMilli();
            String modelId = extractModelId(request.model());
            String responseId = "msg_" + System.currentTimeMillis();

            MessageCreateParams.Builder builder = MessageCreateParams.builder()
                    .model(Model.of(modelId))
                    .maxTokens(request.config() != null && request.config().maxTokens() != null
                            ? request.config().maxTokens() : 4096);

            List<MessageParam> userMessages = new ArrayList<>();
            for (UnifiedMessage msg : request.messages()) {
                if (msg.role() == UnifiedMessage.Role.SYSTEM) {
                    builder.system(extractText(msg));
                } else {
                    userMessages.add(toAnthropicMessage(msg));
                }
            }
            builder.messages(userMessages);

            MessageAccumulator accumulator = MessageAccumulator.create();
            try (var stream = client.messages().createStreaming(builder.build())) {
                stream.stream().forEach(event -> {
                    accumulator.accumulate(event);
                    if (event.isContentBlockDelta()) {
                        var delta = event.asContentBlockDelta().delta();
                        if (delta.isText()) {
                            String text = delta.asText().text();
                            chunkConsumer.accept(LLMStreamChunk.text(responseId, request.model(), text));
                        }
                    }
                });
                var finalMsg = accumulator.message();
                TokenUsage usage = new TokenUsage(
                        (int) finalMsg.usage().inputTokens(),
                        (int) finalMsg.usage().outputTokens()
                );
                chunkConsumer.accept(LLMStreamChunk.done(responseId, request.model(), usage));
            } catch (Exception e) {
                log.error("Anthropic streaming error", e);
                chunkConsumer.accept(new LLMStreamChunk("error", request.model(), null, true, null));
            }
        });
    }

    @Override
    public Object transformToolDefs(List<UnifiedToolDef> tools) {
        return tools.stream().map(t -> {
            Map<String, JsonValue> jsonSchema = t.inputSchema().entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> JsonValue.from(e.getValue())));
            return Tool.builder()
                    .name(t.name())
                    .description(t.description())
                    .inputSchema(Tool.InputSchema.builder()
                            .type(JsonValue.from("object"))
                            .putAllAdditionalProperties(jsonSchema)
                            .build())
                    .build();
        }).toList();
    }

    @Override
    public List<ToolCall> parseToolCalls(Object nativeResponse) {
        if (!(nativeResponse instanceof com.anthropic.models.messages.Message message)) {
            return List.of();
        }
        return message.content().stream()
                .filter(ContentBlock::isToolUse)
                .map(b -> {
                    ToolUseBlock toolUse = b.asToolUse();
                    return new ToolCall(
                            toolUse.id(),
                            toolUse.name(),
                            jsonValueToMap(toolUse._input())
                    );
                })
                .toList();
    }

    // ─── Private Helpers ────────────────────────────────────────────────

    /**
     * CR-006: toolChoice 문자열을 Anthropic ToolChoice로 매핑.
     * - "auto" / null → ToolChoice.ofAuto
     * - "none" → null (tools 전달하되 tool_choice 미설정 → Anthropic 기본 동작)
     * - "required" → ToolChoice.ofAny
     * - 그 외 → ToolChoice.ofTool (특정 도구 강제)
     */
    private ToolChoice mapToolChoice(String toolChoice) {
        return switch (toolChoice) {
            case "auto" -> ToolChoice.ofAuto(ToolChoiceAuto.builder().build());
            case "none" -> ToolChoice.ofNone(ToolChoiceNone.builder().build());
            case "required" -> ToolChoice.ofAny(ToolChoiceAny.builder().build());
            default -> ToolChoice.ofTool(
                    ToolChoiceTool.builder().name(toolChoice).build());
        };
    }

    private String extractModelId(String model) {
        return model.contains("/") ? model.substring(model.indexOf("/") + 1) : model;
    }

    /** UnifiedMessage content에서 텍스트 추출 */
    private String extractText(UnifiedMessage msg) {
        return msg.content().stream()
                .filter(b -> b instanceof com.platform.llm.model.ContentBlock.Text)
                .map(b -> ((com.platform.llm.model.ContentBlock.Text) b).text())
                .reduce("", (a, s) -> a + s);
    }

    private MessageParam toAnthropicMessage(UnifiedMessage msg) {
        return switch (msg.role()) {
            case ASSISTANT -> {
                boolean hasToolUse = msg.content().stream()
                        .anyMatch(b -> b instanceof com.platform.llm.model.ContentBlock.ToolUse);
                if (hasToolUse) {
                    // 어시스턴트가 tool_use 블록 포함 시 → ContentBlockParam 목록으로 변환
                    List<ContentBlockParam> params = msg.content().stream()
                            .map(b -> {
                                if (b instanceof com.platform.llm.model.ContentBlock.Text text) {
                                    return ContentBlockParam.ofText(
                                            TextBlockParam.builder().text(text.text()).build());
                                } else if (b instanceof com.platform.llm.model.ContentBlock.ToolUse tu) {
                                    Map<String, JsonValue> inputJson = tu.input().entrySet().stream()
                                            .collect(Collectors.toMap(
                                                    Map.Entry::getKey,
                                                    e -> JsonValue.from(e.getValue())));
                                    return ContentBlockParam.ofToolUse(
                                            ToolUseBlockParam.builder()
                                                    .id(tu.id())
                                                    .name(tu.name())
                                                    .input(ToolUseBlockParam.Input.builder()
                                                            .additionalProperties(inputJson)
                                                            .build())
                                                    .build());
                                }
                                return ContentBlockParam.ofText(
                                        TextBlockParam.builder().text("").build());
                            })
                            .toList();
                    yield MessageParam.builder()
                            .role(MessageParam.Role.ASSISTANT)
                            .contentOfBlockParams(params)
                            .build();
                }
                // 일반 텍스트 어시스턴트 메시지
                yield MessageParam.builder()
                        .role(MessageParam.Role.ASSISTANT)
                        .content(extractText(msg))
                        .build();
            }
            case TOOL_RESULT -> {
                // tool_result는 Anthropic에서 USER 역할 메시지로 전달
                List<ContentBlockParam> params = msg.content().stream()
                        .filter(b -> b instanceof com.platform.llm.model.ContentBlock.ToolResult)
                        .map(b -> {
                            com.platform.llm.model.ContentBlock.ToolResult tr =
                                    (com.platform.llm.model.ContentBlock.ToolResult) b;
                            return ContentBlockParam.ofToolResult(
                                    ToolResultBlockParam.builder()
                                            .toolUseId(tr.toolUseId())
                                            .content(tr.content())
                                            .build());
                        })
                        .toList();
                yield MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .contentOfBlockParams(params)
                        .build();
            }
            default -> {
                // PRD-111: 멀티모달 — Image 블록 포함 시 ContentBlockParam 목록으로 변환
                boolean hasImage = msg.content().stream()
                        .anyMatch(b -> b instanceof com.platform.llm.model.ContentBlock.Image);
                if (hasImage) {
                    List<ContentBlockParam> params = msg.content().stream()
                            .map(b -> {
                                if (b instanceof com.platform.llm.model.ContentBlock.Image img) {
                                    return toImageBlockParam(img);
                                } else if (b instanceof com.platform.llm.model.ContentBlock.Text text) {
                                    return ContentBlockParam.ofText(
                                            TextBlockParam.builder().text(text.text()).build());
                                }
                                return ContentBlockParam.ofText(
                                        TextBlockParam.builder().text("").build());
                            })
                            .toList();
                    yield MessageParam.builder()
                            .role(MessageParam.Role.USER)
                            .contentOfBlockParams(params)
                            .build();
                }
                yield MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .content(extractText(msg))
                        .build();
            }
        };
    }

    /** PRD-111: Image ContentBlock → Anthropic ImageBlockParam (base64 또는 URL) */
    private ContentBlockParam toImageBlockParam(com.platform.llm.model.ContentBlock.Image img) {
        if (img.isBase64()) {
            Base64ImageSource.MediaType mediaType = switch (img.mediaType()) {
                case "image/png" -> Base64ImageSource.MediaType.IMAGE_PNG;
                case "image/gif" -> Base64ImageSource.MediaType.IMAGE_GIF;
                case "image/webp" -> Base64ImageSource.MediaType.IMAGE_WEBP;
                default -> Base64ImageSource.MediaType.IMAGE_JPEG;
            };
            return ContentBlockParam.ofImage(
                    ImageBlockParam.builder()
                            .source(Base64ImageSource.builder()
                                    .data(img.data())
                                    .mediaType(mediaType)
                                    .build())
                            .build());
        } else if (img.isUrl()) {
            return ContentBlockParam.ofImage(
                    ImageBlockParam.builder()
                            .source(UrlImageSource.builder()
                                    .url(img.url())
                                    .build())
                            .build());
        }
        return ContentBlockParam.ofText(TextBlockParam.builder().text("[unsupported image]").build());
    }

    private LLMResponse toLLMResponse(com.anthropic.models.messages.Message msg,
                                       String modelId, long latencyMs) {
        List<com.platform.llm.model.ContentBlock> content = msg.content().stream()
                .map(block -> {
                    if (block.isText()) {
                        return (com.platform.llm.model.ContentBlock)
                                new com.platform.llm.model.ContentBlock.Text(block.asText().text());
                    } else if (block.isToolUse()) {
                        ToolUseBlock tu = block.asToolUse();
                        // CR-007: structured_output 가상 도구 → Structured 블록으로 변환
                        if ("structured_output".equals(tu.name())) {
                            Map<String, Object> data = jsonValueToMap(tu._input());
                            return (com.platform.llm.model.ContentBlock)
                                    new com.platform.llm.model.ContentBlock.Structured("structured_output", data);
                        }
                        return (com.platform.llm.model.ContentBlock)
                                new com.platform.llm.model.ContentBlock.ToolUse(
                                        tu.id(), tu.name(), jsonValueToMap(tu._input()));
                    }
                    return (com.platform.llm.model.ContentBlock)
                            new com.platform.llm.model.ContentBlock.Text("");
                })
                .toList();

        TokenUsage usage = new TokenUsage(
                (int) msg.usage().inputTokens(),
                (int) msg.usage().outputTokens()
        );

        double[] costPerMillion = COSTS.getOrDefault(extractModelId(modelId), new double[]{3.0, 15.0});
        double cost = (usage.inputTokens() * costPerMillion[0] + usage.outputTokens() * costPerMillion[1]) / 1_000_000;

        String stopReasonStr = msg.stopReason().map(StopReason::asString).orElse("end_turn");
        LLMResponse.FinishReason finishReason = switch (stopReasonStr) {
            case "tool_use"   -> LLMResponse.FinishReason.TOOL_USE;
            case "max_tokens" -> LLMResponse.FinishReason.MAX_TOKENS;
            default           -> LLMResponse.FinishReason.END;
        };

        List<ToolCall> toolCalls = parseToolCalls(msg);
        return new LLMResponse(msg.id(), modelId, content, toolCalls, usage, finishReason, latencyMs, cost);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonValueToMap(Object jsonValue) {
        try {
            String json = MAPPER.writeValueAsString(jsonValue);
            return MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
