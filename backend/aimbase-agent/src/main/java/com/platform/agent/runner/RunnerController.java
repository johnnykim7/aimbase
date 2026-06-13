package com.platform.agent.runner;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agent.runner.dto.RunnerCancelRequest;
import com.platform.agent.runner.dto.RunnerChatRequest;
import com.platform.agent.runner.dto.RunnerChatResponse;
import com.platform.llm.model.LLMRequest;
import com.platform.llm.model.LLMResponse;
import com.platform.llm.model.ModelConfig;
import com.platform.llm.model.UnifiedMessage;
import com.platform.runner.claudecli.ClaudeCliCommandBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CR-071 Phase 2: ClaudeCliRunner HTTP API.
 *
 * <ul>
 *   <li>POST /v1/chat            — 단발 응답</li>
 *   <li>POST /v1/chat/stream     — NDJSON 스트림 (Transfer-Encoding: chunked)</li>
 *   <li>POST /v1/cancel          — 진행 중 실행 취소</li>
 *   <li>GET  /v1/health          — Runner 상태</li>
 * </ul>
 *
 * <p>{@code aimbase.runner.enabled=true} 일 때만 활성화 (--runner-mode 진입 시 자동 설정).
 */
@RestController
@RequestMapping("/v1")
public class RunnerController {

    private static final Logger log = LoggerFactory.getLogger(RunnerController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final String RUNNER_API_KEY_HEADER = "X-Api-Key";

    private final RunnerService service;
    private final RunnerProperties props;
    private final long startedAtEpochMs = System.currentTimeMillis();

    public RunnerController(RunnerService service, RunnerProperties props) {
        this.service = service;
        this.props = props;
    }

    @PostMapping(value = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public RunnerChatResponse chat(@RequestHeader(value = RUNNER_API_KEY_HEADER, required = false) String apiKey,
                                   @RequestBody RunnerChatRequest req) {
        verifyApiKey(apiKey);
        validate(req);

        LLMRequest llmRequest = toLlmRequest(req);
        ClaudeCliCommandBuilder.ToolMode toolMode = parseToolMode(req.getToolMode());

        LLMResponse resp = service.chat(llmRequest, toolMode, req.getConfigDir(),
                req.getSystemPromptOverride(), req.getAllowedTools());
        return toResponse(req.getRunId(), resp);
    }

    @PostMapping(value = "/chat/stream", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StreamingResponseBody> chatStream(
            @RequestHeader(value = RUNNER_API_KEY_HEADER, required = false) String apiKey,
            @RequestBody RunnerChatRequest req) {
        verifyApiKey(apiKey);
        validate(req);

        LLMRequest llmRequest = toLlmRequest(req);
        ClaudeCliCommandBuilder.ToolMode toolMode = parseToolMode(req.getToolMode());

        StreamingResponseBody body = (OutputStream out) -> {
            try {
                LLMResponse resp = service.chatStream(
                        llmRequest, toolMode, req.getConfigDir(), req.getSystemPromptOverride(),
                        delta -> emitNdjsonUnchecked(out, Map.of(
                                "type", "delta",
                                "delta", delta != null ? delta : "")),
                        req.getAllowedTools());
                Map<String, Object> done = new LinkedHashMap<>();
                done.put("type", "result");
                done.put("run_id", req.getRunId());
                if (resp.usage() != null) {
                    Map<String, Object> u = new LinkedHashMap<>();
                    u.put("input_tokens", resp.usage().inputTokens());
                    u.put("output_tokens", resp.usage().outputTokens());
                    done.put("usage", u);
                }
                if (resp.finishReason() != null) done.put("finish_reason", resp.finishReason().name());
                if (resp.toolCalls() != null && !resp.toolCalls().isEmpty()) {
                    done.put("tool_calls", resp.toolCalls().stream().map(tc -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", tc.id());
                        m.put("name", tc.name());
                        m.put("input", tc.input());
                        return m;
                    }).toList());
                }
                emitNdjson(out, done);
                out.flush();
            } catch (RuntimeException e) {
                log.warn("/v1/chat/stream 오류: {}", e.getMessage());
                try {
                    emitNdjson(out, Map.of("type", "error", "message", String.valueOf(e.getMessage())));
                } catch (IOException ignore) {}
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-ndjson"))
                .body(body);
    }

    @PostMapping(value = "/cancel", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> cancel(@RequestHeader(value = RUNNER_API_KEY_HEADER, required = false) String apiKey,
                                      @RequestBody RunnerCancelRequest req) {
        verifyApiKey(apiKey);
        if (req == null || req.getRunId() == null || req.getRunId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "run_id required");
        }
        boolean ok = service.cancel(req.getRunId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cancelled", ok);
        if (!ok) result.put("reason", "not_found_or_failed");
        return result;
    }

    @org.springframework.web.bind.annotation.GetMapping(value = "/health",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("active_runs", service.activeRunCount());
        body.put("max_workers", props.getMaxWorkers());
        body.put("uptime_seconds", (System.currentTimeMillis() - startedAtEpochMs) / 1000L);
        return body;
    }

    // ─── 내부 ─────────────────────────────────────────────────────────────

    private void verifyApiKey(String provided) {
        String expected = props.getApiKey();
        if (expected == null || expected.isBlank()) {
            // 키 미설정 = 인증 비활성 (개발/테스트 모드 전용). 운영에서는 키 필수.
            return;
        }
        if (provided == null || !expected.equals(provided)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid X-Api-Key");
        }
    }

    private void validate(RunnerChatRequest req) {
        if (req == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body required");
        if (req.getRunId() == null || req.getRunId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "run_id required");
        }
        if (req.getMessages() == null || req.getMessages().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "messages required");
        }
    }

    private LLMRequest toLlmRequest(RunnerChatRequest req) {
        List<UnifiedMessage> messages = RunnerService.mapMessages(req.getMessages());
        ModelConfig config = req.getMaxTokens() != null
                ? new ModelConfig(null, req.getMaxTokens(), null, null, null, null, null)
                : ModelConfig.defaults();
        String model = (req.getModel() != null && !req.getModel().isBlank())
                ? req.getModel() : props.getDefaultModel();
        return new LLMRequest(model, messages, null, config, true, req.getRunId());
    }

    private static ClaudeCliCommandBuilder.ToolMode parseToolMode(String raw) {
        if (raw == null || raw.isBlank()) return null; // builder default
        try {
            return ClaudeCliCommandBuilder.ToolMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "invalid tool_mode: " + raw + " (allowed: AIMBASE, NATIVE, HYBRID)");
        }
    }

    private static RunnerChatResponse toResponse(String runId, LLMResponse resp) {
        RunnerChatResponse out = new RunnerChatResponse();
        out.setRunId(runId);
        out.setModel(resp.model());
        out.setContent(resp.textContent());
        if (resp.toolCalls() != null && !resp.toolCalls().isEmpty()) {
            out.setToolCalls(resp.toolCalls().stream().map(tc -> {
                Map<String, Object> m = new HashMap<>();
                m.put("id", tc.id());
                m.put("name", tc.name());
                m.put("input", tc.input());
                return m;
            }).toList());
        }
        if (resp.usage() != null) {
            Map<String, Object> u = new HashMap<>();
            u.put("input_tokens", resp.usage().inputTokens());
            u.put("output_tokens", resp.usage().outputTokens());
            out.setUsage(u);
        }
        if (resp.finishReason() != null) out.setFinishReason(resp.finishReason().name());
        // CR-102: CLI 내부 도구 루프 관찰 운반 (가시화 전용)
        if (resp.hasObservedToolEvents()) {
            out.setObservedToolEvents(resp.observedToolEvents().stream().map(ev -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("tool_name", ev.toolName());
                m.put("input", ev.input());
                if (ev.output() != null) m.put("output", ev.output());
                if (ev.durationMs() != null) m.put("duration_ms", ev.durationMs());
                return m;
            }).toList());
        }
        return out;
    }

    private static void emitNdjson(OutputStream out, Map<String, Object> event) throws IOException {
        try {
            byte[] line = (MAPPER.writeValueAsString(event) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.write(line);
            out.flush();
        } catch (JsonProcessingException e) {
            throw new IOException(e);
        }
    }

    /** Consumer 람다용 — IOException 을 RuntimeException 으로 래핑. */
    private static void emitNdjsonUnchecked(OutputStream out, Map<String, Object> event) {
        try {
            emitNdjson(out, event);
        } catch (IOException e) {
            throw new RuntimeException("NDJSON write 실패", e);
        }
    }
}
