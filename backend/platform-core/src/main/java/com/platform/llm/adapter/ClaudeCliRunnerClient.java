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

    /** CR-106: 설정 미주입(테스트/무인자) 시 HTTP 요청 타임아웃 기본값 (초). */
    static final long DEFAULT_HTTP_TIMEOUT_SECONDS = 300L;

    private final HttpClient httpClient;
    /** CR-106: Runner /v1/chat(/stream) 요청 타임아웃 — 장기 AGENT_CALL 대응 설정화. */
    private final Duration httpRequestTimeout;

    /** Spring 주입용 — application.yml platform.llm.anthropic-cli.http-timeout-seconds. */
    @org.springframework.beans.factory.annotation.Autowired
    public ClaudeCliRunnerClient(
            @org.springframework.beans.factory.annotation.Value(
                    "${platform.llm.anthropic-cli.http-timeout-seconds:" + DEFAULT_HTTP_TIMEOUT_SECONDS + "}")
            long httpTimeoutSeconds) {
        this(HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build(),
                Duration.ofSeconds(httpTimeoutSeconds > 0 ? httpTimeoutSeconds : DEFAULT_HTTP_TIMEOUT_SECONDS));
    }

    /** 기존 무인자 생성자 호환 (테스트). 기본 타임아웃 사용. */
    public ClaudeCliRunnerClient() {
        this(HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build(),
                Duration.ofSeconds(DEFAULT_HTTP_TIMEOUT_SECONDS));
    }

    /** 기존 HttpClient 주입 생성자 호환 (테스트). 기본 타임아웃 사용. */
    public ClaudeCliRunnerClient(HttpClient httpClient) {
        this(httpClient, Duration.ofSeconds(DEFAULT_HTTP_TIMEOUT_SECONDS));
    }

    public ClaudeCliRunnerClient(HttpClient httpClient, Duration httpRequestTimeout) {
        this.httpClient = httpClient;
        this.httpRequestTimeout = httpRequestTimeout != null
                ? httpRequestTimeout : Duration.ofSeconds(DEFAULT_HTTP_TIMEOUT_SECONDS);
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
            boolean sawResult = false;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    try {
                        Map<String, Object> evt = MAPPER.readValue(line, MAP_TYPE);
                        if ("result".equals(evt.get("type"))) sawResult = true;
                        handleStreamEvent(evt, runId, model, chunkConsumer);
                    } catch (Exception parse) {
                        log.trace("Runner stream 라인 파싱 실패 (스킵): {}", line);
                    }
                }
            }
            // CR-111: result(정상 종료) 이벤트 없이 스트림이 끊기면 — agent 의 async timeout(또는 연결 절단)으로
            // turn 이 도구 호출 직후 interrupted 된 것. 부분 delta 를 정상 완료로 흘려보내면 호출 측이
            // COMPLETED 로 오판해 다음 워크플로우 스텝에 빈/미완 결과를 넘긴다 → 명시 실패로 승격해 retry 정책에 태운다.
            // (error 이벤트는 handleStreamEvent 가 이미 예외로 승격하므로 여기 도달 안 함.)
            if (!sawResult) {
                throw new RuntimeException(
                        "Runner stream ended without result event (turn truncated — likely agent async timeout)");
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException("Runner /v1/chat/stream 호출 실패: " + e.getMessage(), e);
        }
    }

    /** CR-109: cancel 은 정리 신호이므로 짧은 타임아웃 — 장기 chat timeout(기본 300s)을 기다리면 안 된다. */
    private static final Duration CANCEL_TIMEOUT = Duration.ofSeconds(10);

    public void cancel(AgentEndpoint endpoint, String runId, String runnerApiKey) {
        Map<String, Object> body = Map.of("run_id", runId);
        HttpRequest httpReq = newJsonRequest(endpoint, "/v1/cancel", body, runnerApiKey, CANCEL_TIMEOUT);
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

    private HttpRequest newJsonRequest(AgentEndpoint endpoint, String path,
                                        Map<String, Object> body, String runnerApiKey) {
        return newJsonRequest(endpoint, path, body, runnerApiKey, httpRequestTimeout);
    }

    /** CR-109: 요청별 타임아웃 오버라이드 오버로드 (cancel 은 짧은 타임아웃 사용). */
    private HttpRequest newJsonRequest(AgentEndpoint endpoint, String path,
                                        Map<String, Object> body, String runnerApiKey,
                                        Duration timeout) {
        try {
            String json = MAPPER.writeValueAsString(body);
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(endpoint.runnerEndpoint() + path))
                    .header("Content-Type", "application/json")
                    .timeout(timeout != null ? timeout : httpRequestTimeout)  // CR-106: 설정화 / CR-109: 요청별 override
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
        // CR-107 후속: CLI 프로세스 cwd 로 쓸 작업장 절대경로. Runner 가 Worker.pb.directory 로 설정 →
        // HYBRID CLI 내장 Read/Bash 가 상대경로(attachments/...)로도 작업장을 읽는다. 비면 미전송(기존 동작).
        if (request.workingDirectory() != null && !request.workingDirectory().isBlank()) {
            body.put("working_directory", request.workingDirectory());
        }
        // CR-107 디버그: BE 가 agent 로 보내는 working_directory 추적.
        org.slf4j.LoggerFactory.getLogger(ClaudeCliRunnerClient.class)
                .info("[CR107-DEBUG] runner body: runId={}, working_directory(sent)={}",
                        request.sessionId(), request.workingDirectory());
        // CR-104: 이 호출의 도구 목록(= getToolDefs(toolFilter), API 경로가 모델에 싣는 것과 동일)을
        // 원본 도구명으로 실어 보낸다. Runner 가 mcp__aimbase-server__<tool> 로 변환해 --allowedTools 주입.
        // 비어있으면 미전송 → Runner 가 서버 MCP endpoint 노출 전체(CLI_EXPOSED)를 그대로 사용.
        if (request.tools() != null && !request.tools().isEmpty()) {
            List<String> toolNames = request.tools().stream()
                    .map(com.platform.tool.model.UnifiedToolDef::name)
                    .filter(n -> n != null && !n.isBlank())
                    .distinct()
                    .toList();
            if (!toolNames.isEmpty()) {
                body.put("allowed_tools", toolNames);
            }
        }
        body.put("messages", toRawMessages(request.messages()));
        return body;
    }

    /**
     * UnifiedMessage → Runner HTTP body 의 messages 항목.
     * CR-098: 멀티모달(Document/Image) 블록이 있으면 content 를 Anthropic content block 배열로 보존한다.
     * (claude CLI stream-json 이 받는 포맷과 동일 — Runner 쪽 ClaudeCliWorker 가 그대로 stdin 에 흘려보냄)
     * 멀티모달이 없으면 기존처럼 텍스트 한 줄(String)로 단순화 — 하위호환.
     */
    private static List<Map<String, Object>> toRawMessages(List<UnifiedMessage> messages) {
        if (messages == null) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (UnifiedMessage m : messages) {
            String role = m.role() != null ? m.role().name().toLowerCase() : "user";
            boolean hasMultimodal = m.content().stream()
                    .anyMatch(b -> b instanceof ContentBlock.Image || b instanceof ContentBlock.Document);
            if (hasMultimodal) {
                List<Map<String, Object>> blocks = new ArrayList<>();
                for (ContentBlock b : m.content()) {
                    Map<String, Object> blk = toAnthropicBlock(b);
                    if (blk != null) blocks.add(blk);
                }
                Map<String, Object> msg = new HashMap<>();
                msg.put("role", role);
                msg.put("content", blocks);
                out.add(msg);
            } else {
                String text = m.content().stream()
                        .filter(b -> b instanceof ContentBlock.Text)
                        .map(b -> ((ContentBlock.Text) b).text())
                        .reduce("", (a, b) -> a + b);
                out.add(Map.of("role", role, "content", text));
            }
        }
        return out;
    }

    /**
     * CR-098: ContentBlock → Anthropic API content block(Map). base64 source 만 지원(URL 미지원).
     * text/image/document 외 타입은 null(전달 생략).
     */
    private static Map<String, Object> toAnthropicBlock(ContentBlock b) {
        if (b instanceof ContentBlock.Text t) {
            return Map.of("type", "text", "text", t.text() != null ? t.text() : "");
        }
        if (b instanceof ContentBlock.Image img && img.isBase64()) {
            return Map.of("type", "image", "source", Map.of(
                    "type", "base64", "media_type", img.mediaType(), "data", img.data()));
        }
        if (b instanceof ContentBlock.Document doc && doc.isBase64()) {
            return Map.of("type", "document", "source", Map.of(
                    "type", "base64", "media_type", doc.mediaType(), "data", doc.data()));
        }
        return null;
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

        // CR-102: CLI 내부 도구 루프 관찰 복원 (가시화 전용 — toolCalls 와 분리)
        List<com.platform.llm.model.ObservedToolEvent> observed = null;
        Object rawObserved = json.get("observed_tool_events");
        if (rawObserved instanceof List<?> list && !list.isEmpty()) {
            observed = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> ev) {
                    Object input = ev.get("input");
                    Map<String, Object> inputMap = (input instanceof Map<?, ?>)
                            ? new HashMap<>((Map<String, Object>) input)
                            : Map.of();
                    Object dur = ev.get("duration_ms");
                    observed.add(new com.platform.llm.model.ObservedToolEvent(
                            ev.get("tool_name") != null ? ev.get("tool_name").toString() : null,
                            inputMap,
                            ev.get("output") != null ? ev.get("output").toString() : null,
                            dur instanceof Number n ? n.longValue() : null));
                }
            }
        }

        return new LLMResponse(runId, model, blocks, toolCalls, usage, finishReason, 0L, 0.0, observed);
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
        } else if ("tool_use".equals(type) || "tool_result".equals(type)) {
            // CR-108: CLI 내부 도구 관찰을 turn 도중 실시간 운반 — observedToolUse/Result chunk.
            Object input = evt.get("input");
            Map<String, Object> inputMap = (input instanceof Map<?, ?>)
                    ? new HashMap<>((Map<String, Object>) input) : null;
            Object dur = evt.get("duration_ms");
            LLMStreamChunk.ObservedTool obs = new LLMStreamChunk.ObservedTool(
                    evt.get("tool_use_id") != null ? evt.get("tool_use_id").toString() : null,
                    evt.get("tool_name") != null ? evt.get("tool_name").toString() : null,
                    inputMap,
                    evt.get("output") != null ? evt.get("output").toString() : null,
                    dur instanceof Number n ? n.longValue() : null);
            consumer.accept("tool_result".equals(type)
                    ? LLMStreamChunk.observedToolResult(runId, model, obs)
                    : LLMStreamChunk.observedToolUse(runId, model, obs));
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
