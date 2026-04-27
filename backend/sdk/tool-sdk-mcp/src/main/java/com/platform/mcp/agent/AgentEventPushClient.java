package com.platform.mcp.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CR-070 Phase B: Agent → 서버 진행 이벤트 push 클라이언트.
 *
 * Agent 측 ClaudeCodeTool 등 자율 도구가 NDJSON 라인을 생성할 때마다
 * 서버 `POST /api/v1/agents/{agentId}/runs/{runId}/events` 로 송신.
 *
 * 정책:
 * - 단발 이벤트마다 application/json 으로 pushOne 호출 (단순화)
 * - 손실 허용 — 실패 시 silent log only (재전송/ACK 없음)
 * - JDK HttpClient 재사용 (가비지 최소화)
 *
 * 손실 허용을 전제로 한 단순 구현. 후속 CR 에서 chunked NDJSON 단일 연결로 진화 가능.
 */
public class AgentEventPushClient {

    private static final Logger log = LoggerFactory.getLogger(AgentEventPushClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String aimbaseBaseUrl;
    private final String apiKey;
    private final String agentId;
    private final String runId;
    private final HttpClient httpClient;
    private final Duration sendTimeout;

    public AgentEventPushClient(String aimbaseBaseUrl, String apiKey,
                                String agentId, String runId,
                                HttpClient httpClient) {
        this.aimbaseBaseUrl = stripSlash(aimbaseBaseUrl);
        this.apiKey = apiKey;
        this.agentId = agentId;
        this.runId = runId;
        this.httpClient = httpClient != null ? httpClient
                : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.sendTimeout = Duration.ofSeconds(5);
    }

    public AgentEventPushClient(String aimbaseBaseUrl, String apiKey,
                                String agentId, String runId) {
        this(aimbaseBaseUrl, apiKey, agentId, runId, null);
    }

    /**
     * 단일 이벤트 push.
     *
     * @param type "text_delta" | "tool_use_start" | "tool_result"
     * @param payload 이벤트별 필드 (delta / id, name, input / tool_use_id, output, is_error)
     * @return true = HTTP 2xx, false = 실패 (예외는 삼킴)
     */
    public boolean push(String type, Map<String, Object> payload) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", type);
            if (payload != null) body.putAll(payload);

            String url = aimbaseBaseUrl + "/api/v1/agents/" + agentId
                    + "/runs/" + runId + "/events";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("X-Api-Key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .timeout(sendTimeout)
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            boolean ok = code >= 200 && code < 300;
            if (!ok) {
                log.debug("AgentEventPushClient push 실패 type={} status={} body={}",
                        type, code, response.body());
            }
            return ok;
        } catch (Exception e) {
            log.debug("AgentEventPushClient push 예외 type={}: {}", type, e.getMessage());
            return false;
        }
    }

    public boolean pushTextDelta(String delta) {
        return push("text_delta", Map.of("delta", delta == null ? "" : delta));
    }

    public boolean pushToolUseStart(String id, String name, Map<String, Object> input) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id == null ? "" : id);
        payload.put("name", name == null ? "" : name);
        payload.put("input", input == null ? Map.of() : input);
        return push("tool_use_start", payload);
    }

    public boolean pushToolResult(String toolUseId, String output, boolean isError) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool_use_id", toolUseId == null ? "" : toolUseId);
        payload.put("output", output == null ? "" : output);
        payload.put("is_error", isError);
        return push("tool_result", payload);
    }

    public String getRunId() {
        return runId;
    }

    private static String stripSlash(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
