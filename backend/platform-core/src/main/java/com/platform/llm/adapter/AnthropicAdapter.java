package com.platform.llm.adapter;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.CacheControlEphemeral;
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
import com.anthropic.models.messages.ThinkingBlock;
import com.anthropic.models.messages.ThinkingConfigEnabled;
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
    private final int defaultMaxTokens;

    // [input$/MTok, output$/MTok, cacheWrite$/MTok, cacheRead$/MTok]
    private static final Map<String, double[]> COSTS = Map.of(
            "claude-opus-4-6",           new double[]{15.0,  75.0, 18.75, 1.50},
            "claude-sonnet-4-6",         new double[]{ 3.0,  15.0,  3.75, 0.30},
            "claude-sonnet-4-5",         new double[]{ 3.0,  15.0,  3.75, 0.30},
            "claude-haiku-4-5-20251001", new double[]{ 0.25,  1.25, 0.30, 0.03}
    );

    private static final CacheControlEphemeral CACHE_EPHEMERAL =
            CacheControlEphemeral.builder().build();

    public AnthropicAdapter(AnthropicClient client,
                           @org.springframework.beans.factory.annotation.Value("${platform.orchestrator.default-max-tokens:16000}") int defaultMaxTokens) {
        this.client = client;
        this.defaultMaxTokens = defaultMaxTokens;
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
                            ? request.config().maxTokens() : defaultMaxTokens);

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
            // Prompt Cache: system prompt를 TextBlockParam 목록으로 전달하고 마지막 블록에 cache_control 적용
            // 5분 ephemeral 캐시 → 반복 호출 시 cache_read 요금(0.1x)만 과금
            if (!systemText.isEmpty()) {
                builder.systemOfTextBlockParams(List.of(
                        TextBlockParam.builder()
                                .text(systemText.toString())
                                .cacheControl(CACHE_EPHEMERAL)
                                .build()
                ));
            }
            builder.messages(userMessages);

            // CR-031 PRD-214: Adaptive Thinking — 3모드 분기
            com.platform.llm.model.ThinkingMode thinkingMode = request.config() != null
                    ? request.config().resolveThinkingMode()
                    : com.platform.llm.model.ThinkingMode.DISABLED;

            if (thinkingMode == com.platform.llm.model.ThinkingMode.ADAPTIVE) {
                // ADAPTIVE: 모델이 자동으로 budget 결정
                // Claude 4.6+ 모델에서만 지원. 미지원 시 ENABLED로 폴백.
                String reqModel = request.model() != null ? request.model() : "";
                if (reqModel.contains("opus-4-6") || reqModel.contains("sonnet-4-6")) {
                    builder.enabledThinking(0); // budget=0 → API가 adaptive로 처리
                } else {
                    // 미지원 모델 → ENABLED 폴백 (기본 budget 10000)
                    int budget = request.config().thinkingBudgetTokens() != null
                            ? request.config().thinkingBudgetTokens() : 10000;
                    int maxTok = request.config().maxTokens() != null
                            ? request.config().maxTokens() : 16000;
                    budget = Math.max(1024, Math.min(budget, maxTok - 1));
                    builder.enabledThinking(budget);
                    log.debug("ADAPTIVE thinking 미지원 모델 '{}' → ENABLED 폴백 (budget={})", modelId, budget);
                }
            } else if (thinkingMode == com.platform.llm.model.ThinkingMode.ENABLED) {
                int budget = request.config().thinkingBudgetTokens() != null
                        ? request.config().thinkingBudgetTokens() : 10000;
                int maxTok = request.config().maxTokens() != null
                        ? request.config().maxTokens() : 16000;
                budget = Math.max(1024, Math.min(budget, maxTok - 1));
                builder.enabledThinking(budget);
            } else if (request.config() != null && request.config().temperature() != null) {
                // DISABLED — temperature 설정 가능 (thinking 비활성 시만)
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
                // Prompt Cache: 마지막 도구 정의에 cache_control 적용 (도구 목록 전체 캐시)
                List<Tool> cachedTools = new ArrayList<>(anthropicTools);
                if (!cachedTools.isEmpty()) {
                    int last = cachedTools.size() - 1;
                    cachedTools.set(last, cachedTools.get(last).toBuilder()
                            .cacheControl(CACHE_EPHEMERAL)
                            .build());
                }
                List<ToolUnion> toolUnions = cachedTools.stream()
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
                            ? request.config().maxTokens() : defaultMaxTokens);

            List<MessageParam> userMessages = new ArrayList<>();
            StringBuilder streamSystemText = new StringBuilder();
            for (UnifiedMessage msg : request.messages()) {
                if (msg.role() == UnifiedMessage.Role.SYSTEM) {
                    streamSystemText.append(extractText(msg));
                } else {
                    userMessages.add(toAnthropicMessage(msg));
                }
            }
            if (!streamSystemText.isEmpty()) {
                builder.systemOfTextBlockParams(List.of(
                        TextBlockParam.builder()
                                .text(streamSystemText.toString())
                                .cacheControl(CACHE_EPHEMERAL)
                                .build()
                ));
            }
            builder.messages(userMessages);

            // CR-031 PRD-214: 스트리밍에도 Adaptive Thinking 3모드 적용
            com.platform.llm.model.ThinkingMode streamThinkingMode = request.config() != null
                    ? request.config().resolveThinkingMode()
                    : com.platform.llm.model.ThinkingMode.DISABLED;

            if (streamThinkingMode == com.platform.llm.model.ThinkingMode.ADAPTIVE) {
                String reqModel = request.model() != null ? request.model() : "";
                if (reqModel.contains("opus-4-6") || reqModel.contains("sonnet-4-6")) {
                    builder.enabledThinking(0);
                } else {
                    int budget = request.config().thinkingBudgetTokens() != null
                            ? request.config().thinkingBudgetTokens() : 10000;
                    int maxTok = request.config().maxTokens() != null
                            ? request.config().maxTokens() : 16000;
                    budget = Math.max(1024, Math.min(budget, maxTok - 1));
                    builder.enabledThinking(budget);
                }
            } else if (streamThinkingMode == com.platform.llm.model.ThinkingMode.ENABLED) {
                int budget = request.config().thinkingBudgetTokens() != null
                        ? request.config().thinkingBudgetTokens() : 10000;
                int maxTok = request.config().maxTokens() != null
                        ? request.config().maxTokens() : 16000;
                budget = Math.max(1024, Math.min(budget, maxTok - 1));
                builder.enabledThinking(budget);
            }

            MessageAccumulator accumulator = MessageAccumulator.create();
            try (var stream = client.messages().createStreaming(builder.build())) {
                stream.stream().forEach(event -> {
                    accumulator.accumulate(event);
                    if (event.isContentBlockDelta()) {
                        var delta = event.asContentBlockDelta().delta();
                        if (delta.isText()) {
                            String text = delta.asText().text();
                            chunkConsumer.accept(LLMStreamChunk.text(responseId, request.model(), text));
                        } else if (delta.isThinking()) {
                            // CR-030: thinking delta는 별도 타입으로 전달
                            String thinking = delta.asThinking().thinking();
                            chunkConsumer.accept(LLMStreamChunk.thinking(responseId, request.model(), thinking));
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
                    // CR-030: Extended Thinking 블록 처리
                    if (block.isThinking()) {
                        ThinkingBlock tb = block.asThinking();
                        return (com.platform.llm.model.ContentBlock)
                                new com.platform.llm.model.ContentBlock.Thinking(
                                        tb.thinking(), tb.signature());
                    } else if (block.isText()) {
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

        int cacheCreate = msg.usage().cacheCreationInputTokens().orElse(0L).intValue();
        int cacheRead   = msg.usage().cacheReadInputTokens().orElse(0L).intValue();
        TokenUsage usage = new TokenUsage(
                (int) msg.usage().inputTokens(),
                (int) msg.usage().outputTokens(),
                cacheCreate,
                cacheRead
        );

        // [input, output, cacheWrite, cacheRead] in $/MTok
        double[] c = COSTS.getOrDefault(extractModelId(modelId), new double[]{3.0, 15.0, 3.75, 0.30});
        double cost = (usage.inputTokens()              * c[0]
                     + usage.outputTokens()             * c[1]
                     + usage.cacheCreationInputTokens() * c[2]
                     + usage.cacheReadInputTokens()     * c[3]) / 1_000_000;

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
