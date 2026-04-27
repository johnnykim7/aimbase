package com.platform.llm.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.LLMRequest;
import com.platform.llm.model.LLMResponse;
import com.platform.llm.model.LLMStreamChunk;
import com.platform.llm.model.TokenUsage;
import com.platform.llm.model.ToolCall;
import com.platform.llm.model.UnifiedMessage;
import com.platform.service.AgentEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * CR-071 Phase 4: ClaudeCliAdapter 가 ClaudeCliRunner 를 HTTP 로 호출하기 위한 클라이언트.
 *
 * <p>Endpoint: {@link AgentEndpoint#runnerEndpoint()}.
 * <ul>
 *   <li>POST /v1/chat        — 단발 응답 (LLMResponse)</li>
 *   <li>POST /v1/chat/stream — NDJSON 스트림 → 라인 단위로 chunk consumer 에 전달</li>
 *   <li>POST /v1/cancel      — 진행 중 취소</li>
 * </ul>
 *
 * <p>인증: 현재는 평문 X-Api-Key 가 클라이언트 측에서 직접 주어지지 않고,
 * 서버 보유 plaintext 키 ↔ AgentRegistry 의 hash 비교는 향후 재정리 예정.
 * Phase 4 단계에서는 connection.config.runner_api_key 를 우선 사용 (없으면 미인증).
 */
@Component
public class ClaudeCliRunnerClient {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCliRunnerClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    public static final String API_KEY_HEADER = "X-Api-Key";

    private final HttpClient httpClient;

    public ClaudeCliRunnerClient() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    public ClaudeCliRunnerClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public LLMResponse chat(AgentEndpoint endpoint, LLMRequest request, String toolMode,
                             String configDir, String systemPromptOverride, String runnerApiKey) {
        Map<String, Object> body = buildBody(request, toolMode, configDir, systemPromptOverride);
        HttpRequest httpReq = newJsonRequest(endpoint, "/v1/chat", body, runnerApiKey);

        try {
            HttpResponse<String> resp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new ResponseStatusException(HttpStatus.valueOf(resp.statusCode()),
                        "Runner /v1/chat 실패: " + resp.body());
            }
            Map<String, Object> json = MAPPER.readValue(resp.body(), MAP_TYPE);
            return toLLMResponse(json, request);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException("Runner /v1/chat 호출 실패: " + e.getMessage(), e);
        }
    }

    public void chatStream(AgentEndpoint endpoint, LLMRequest request, String toolMode,
                            String configDir, String systemPromptOverride, String runnerApiKey,
                            Consumer<LLMStreamChunk> chunkConsumer) {
        Map<String, Object> body = buildBody(request, toolMode, configDir, systemPromptOverride);
        HttpRequest httpReq = newJsonRequest(endpoint, "/v1/chat/stream", body, runnerApiKey);

        try {
            HttpResponse<InputStream> resp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                throw new ResponseStatusException(HttpStatus.valueOf(resp.statusCode()),
                        "Runner /v1/chat/stream 실패");
            }
            String runId = request.sessionId();
            String model = request.model();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    try {
                        Map<String, Object> evt = MAPPER.readValue(line, MAP_TYPE);
                        handleStreamEvent(evt, runId, model, chunkConsumer);
                    } catch (Exception parse) {
                        log.trace("Runner stream 라인 파싱 실패 (스킵): {}", line);
                    }
                }
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException("Runner /v1/chat/stream 호출 실패: " + e.getMessage(), e);
        }
    }

    public void cancel(AgentEndpoint endpoint, String runId, String runnerApiKey) {
        Map<String, Object> body = Map.of("run_id", runId);
        HttpRequest httpReq = newJsonRequest(endpoint, "/v1/cancel", body, runnerApiKey);
        try {
            HttpResponse<String> resp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("Runner /v1/cancel 응답 {}: {}", resp.statusCode(), resp.body());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Runner /v1/cancel 호출 실패: {}", e.getMessage());
        }
    }

    // ─── 내부 ─────────────────────────────────────────────────────────────

    private static HttpRequest newJsonRequest(AgentEndpoint endpoint, String path,
                                               Map<String, Object> body, String runnerApiKey) {
        try {
            String json = MAPPER.writeValueAsString(body);
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(endpoint.runnerEndpoint() + path))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(300))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
            if (runnerApiKey != null && !runnerApiKey.isBlank()) {
                b.header(API_KEY_HEADER, runnerApiKey);
            }
            return b.build();
        } catch (Exception e) {
            throw new RuntimeException("Runner 요청 직렬화 실패: " + e.getMessage(), e);
        }
    }

    private static Map<String, Object> buildBody(LLMRequest request, String toolMode,
                                                   String configDir, String systemPromptOverride) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("run_id", request.sessionId() != null ? request.sessionId() : UUID.randomUUID().toString());
        body.put("model", request.model());
        if (toolMode != null && !toolMode.isBlank()) body.put("tool_mode", toolMode);
        if (configDir != null && !configDir.isBlank()) body.put("config_dir", configDir);
        if (systemPromptOverride != null && !systemPromptOverride.isBlank()) {
            body.put("system_prompt_override", systemPromptOverride);
        }
        if (request.config() != null && request.config().maxTokens() != null) {
            body.put("max_tokens", request.config().maxTokens());
        }
        body.put("messages", toRawMessages(request.messages()));
        return body;
    }

    private static List<Map<String, Object>> toRawMessages(List<UnifiedMessage> messages) {
        if (messages == null) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (UnifiedMessage m : messages) {
            String role = m.role() != null ? m.role().name().toLowerCase() : "user";
            String text = m.content().stream()
                    .filter(b -> b instanceof ContentBlock.Text)
                    .map(b -> ((ContentBlock.Text) b).text())
                    .reduce("", (a, b) -> a + b);
            out.add(Map.of("role", role, "content", text));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static LLMResponse toLLMResponse(Map<String, Object> json, LLMRequest request) {
        String runId = (String) json.getOrDefault("run_id", request.sessionId());
        String model = (String) json.getOrDefault("model", request.model());
        String content = (String) json.getOrDefault("content", "");

        List<ContentBlock> blocks = List.of(new ContentBlock.Text(content != null ? content : ""));

        List<ToolCall> toolCalls = new ArrayList<>();
        Object rawToolCalls = json.get("tool_calls");
        if (rawToolCalls instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> tc) {
                    String id = String.valueOf(tc.get("id"));
                    String name = String.valueOf(tc.get("name"));
                    Object input = tc.get("input");
                    Map<String, Object> inputMap = (input instanceof Map<?, ?>)
                            ? new HashMap<>((Map<String, Object>) input)
                            : new HashMap<>();
                    toolCalls.add(new ToolCall(id, name, inputMap));
                }
            }
        }

        TokenUsage usage = parseUsage(json.get("usage"));

        LLMResponse.FinishReason finishReason = parseFinishReason(json.get("finish_reason"));

        return new LLMResponse(runId, model, blocks, toolCalls, usage, finishReason, 0L, 0.0);
    }

    private static TokenUsage parseUsage(Object raw) {
        if (raw instanceof Map<?, ?> u) {
            int input = numberOf(u.get("input_tokens"));
            int output = numberOf(u.get("output_tokens"));
            return new TokenUsage(input, output);
        }
        return new TokenUsage(0, 0);
    }

    private static int numberOf(Object o) {
        if (o instanceof Number n) return n.intValue();
        return 0;
    }

    private static LLMResponse.FinishReason parseFinishReason(Object raw) {
        if (raw == null) return LLMResponse.FinishReason.END;
        try {
            return LLMResponse.FinishReason.valueOf(raw.toString().toUpperCase());
        } catch (IllegalArgumentException e) {
            return LLMResponse.FinishReason.END;
        }
    }

    @SuppressWarnings("unchecked")
    private static void handleStreamEvent(Map<String, Object> evt, String runId, String model,
                                            Consumer<LLMStreamChunk> consumer) {
        Object type = evt.get("type");
        if ("delta".equals(type)) {
            String delta = String.valueOf(evt.getOrDefault("delta", ""));
            consumer.accept(LLMStreamChunk.text(runId, model, delta));
        } else if ("result".equals(type)) {
            TokenUsage usage = parseUsage(evt.get("usage"));
            LLMResponse.FinishReason finish = parseFinishReason(evt.get("finish_reason"));
            List<ToolCall> toolCalls = new ArrayList<>();
            Object rawTc = evt.get("tool_calls");
            if (rawTc instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> tc) {
                        Object input = tc.get("input");
                        Map<String, Object> inputMap = (input instanceof Map<?, ?>)
                                ? new HashMap<>((Map<String, Object>) input)
                                : new HashMap<>();
                        toolCalls.add(new ToolCall(
                                String.valueOf(tc.get("id")),
                                String.valueOf(tc.get("name")),
                                inputMap));
                    }
                }
            }
            consumer.accept(LLMStreamChunk.done(runId, model, usage, finish, toolCalls));
        } else if ("error".equals(type)) {
            String msg = String.valueOf(evt.getOrDefault("message", "runner error"));
            throw new RuntimeException("Runner stream error: " + msg);
        }
    }
}
