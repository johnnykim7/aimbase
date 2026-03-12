package com.platform.policy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.mcp.MCPServerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;

/**
 * Python Safety MCP Server 호출 클라이언트.
 *
 * Presidio 기반 PII 탐지/마스킹을 Python 사이드카에 위임.
 * 연결 실패 시 기존 Java PIIMasker 폴백.
 *
 * Sprint 16 (PY-009): PII Detection & Masking.
 */
@Component
public class MCPSafetyClient {

    private static final Logger log = LoggerFactory.getLogger(MCPSafetyClient.class);

    private final ObjectMapper objectMapper;

    @Value("${safety.mcp.url:http://localhost:8001}")
    private String mcpServerUrl;

    @Value("${safety.mcp.enabled:false}")
    private boolean mcpEnabled;

    private MCPServerClient mcpClient;
    private boolean connected = false;

    public MCPSafetyClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        if (mcpEnabled) {
            try {
                mcpClient = new MCPServerClient("safety", "sse", Map.of("url", mcpServerUrl));
                mcpClient.connect();
                connected = true;
                log.info("Connected to Safety MCP Server at {}", mcpServerUrl);
            } catch (Exception e) {
                log.warn("Failed to connect to Safety MCP Server at {}: {}. Falling back to Java PIIMasker.",
                        mcpServerUrl, e.getMessage());
                connected = false;
            }
        }
    }

    @PreDestroy
    public void destroy() {
        if (mcpClient != null) {
            mcpClient.close();
        }
    }

    public boolean isAvailable() {
        return mcpEnabled && connected;
    }

    /**
     * PII 탐지 — detect_pii 도구 호출.
     *
     * @return {detections: [{entity_type, start, end, score, text}], count: N}
     */
    public Map<String, Object> detectPii(String text) {
        Map<String, Object> input = Map.of(
                "text", text,
                "language", "ko"
        );

        String result = mcpClient.callTool("detect_pii", input);
        return parseJson(result);
    }

    /**
     * PII 마스킹 — mask_pii 도구 호출.
     *
     * @return {masked_text: "...", detections: [...], pii_found: true/false}
     */
    public Map<String, Object> maskPii(String text) {
        Map<String, Object> input = Map.of(
                "text", text,
                "language", "ko"
        );

        String result = mcpClient.callTool("mask_pii", input);
        return parseJson(result);
    }

    /**
     * LLM 출력 검증 — validate_output 도구 호출.
     *
     * @return {safe: true/false, violations: [...], violation_count: N}
     */
    public Map<String, Object> validateOutput(String text) {
        Map<String, Object> input = Map.of(
                "text", text,
                "language", "ko"
        );

        String result = mcpClient.callTool("validate_output", input);
        return parseJson(result);
    }

    private Map<String, Object> parseJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse Safety MCP response: " + json, e);
        }
    }
}
