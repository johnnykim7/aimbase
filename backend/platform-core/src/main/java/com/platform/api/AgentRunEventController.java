package com.platform.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agent.AgentRunEventRouter;
import com.platform.orchestrator.stream.StreamEvent;
import com.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * CR-070 Phase B: Agent → 서버 진행 이벤트 수신 엔드포인트.
 *
 * Agent 가 ClaudeCodeTool 등 자율 실행 도구의 진행상황을 NDJSON 으로 push 하면,
 * AgentRunEventRouter 에 등록된 사용자 SSE sink 로 dispatch.
 *
 * 입력 포맷:
 *   POST /api/v1/agents/{agentId}/runs/{runId}/events
 *   Content-Type: application/x-ndjson
 *   X-Api-Key: <agent key>
 *
 *   {"type":"text_delta","delta":"..."}
 *   {"type":"tool_use_start","id":"...","name":"...","input":{...}}
 *   {"type":"tool_result","tool_use_id":"...","output":"...","is_error":false}
 *
 * 손실 허용 / 순서 보장 (단일 HTTP 연결 내) / X-Api-Key 인증 / 테넌트는 TenantContext 기반.
 */
@RestController
@RequestMapping("/api/v1/agents/{agentId}/runs/{runId}/events")
public class AgentRunEventController {

    private static final Logger log = LoggerFactory.getLogger(AgentRunEventController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentRunEventRouter router;

    public AgentRunEventController(AgentRunEventRouter router) {
        this.router = router;
    }

    @PostMapping(consumes = "application/x-ndjson")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> push(@PathVariable UUID agentId,
                                    @PathVariable String runId,
                                    HttpServletRequest request) {
        String tenantId = TenantContext.getTenantId();
        int dispatched = 0;
        int dropped = 0;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(request.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                StreamEvent event = parseEvent(trimmed);
                if (event == null) {
                    dropped++;
                    continue;
                }
                if (router.dispatch(runId, tenantId, event)) {
                    dispatched++;
                } else {
                    dropped++;
                }
            }
        } catch (IOException e) {
            log.warn("AgentRunEventController stream read 실패 runId={}: {}", runId, e.getMessage());
        }

        log.debug("AgentRunEventController agentId={} runId={} dispatched={} dropped={}",
                agentId, runId, dispatched, dropped);
        return Map.of("dispatched", dispatched, "dropped", dropped);
    }

    /**
     * 단일 이벤트 JSON push (간단한 테스트/단발 이벤트 용).
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> pushOne(@PathVariable UUID agentId,
                                       @PathVariable String runId,
                                       @org.springframework.web.bind.annotation.RequestBody Map<String, Object> body) {
        String tenantId = TenantContext.getTenantId();
        StreamEvent event = mapToEvent(body);
        if (event == null) {
            return Map.of("dispatched", 0, "dropped", 1, "reason", "invalid_event");
        }
        boolean ok = router.dispatch(runId, tenantId, event);
        return Map.of("dispatched", ok ? 1 : 0, "dropped", ok ? 0 : 1);
    }

    @SuppressWarnings("unchecked")
    private StreamEvent parseEvent(String line) {
        try {
            Map<String, Object> map = MAPPER.readValue(line, Map.class);
            return mapToEvent(map);
        } catch (Exception e) {
            log.trace("이벤트 라인 파싱 실패: {}", line);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private StreamEvent mapToEvent(Map<String, Object> map) {
        Object typeObj = map.get("type");
        if (!(typeObj instanceof String type)) return null;
        return switch (type) {
            case "text_delta" -> {
                Object delta = map.get("delta");
                yield delta instanceof String s ? new StreamEvent.TextDelta(s) : null;
            }
            case "tool_use_start" -> {
                Object id = map.get("id");
                Object name = map.get("name");
                Object input = map.get("input");
                Map<String, Object> inputMap = (input instanceof Map<?, ?> mp)
                        ? (Map<String, Object>) mp : Map.of();
                yield new StreamEvent.ToolUseStart(
                        id instanceof String s ? s : "",
                        name instanceof String n ? n : "",
                        inputMap);
            }
            case "tool_result" -> {
                Object id = map.get("tool_use_id");
                Object output = map.get("output");
                Object err = map.get("is_error");
                yield new StreamEvent.ToolResultEvent(
                        id instanceof String s ? s : "",
                        output == null ? "" : output.toString(),
                        Boolean.TRUE.equals(err));
            }
            default -> null;
        };
    }
}
