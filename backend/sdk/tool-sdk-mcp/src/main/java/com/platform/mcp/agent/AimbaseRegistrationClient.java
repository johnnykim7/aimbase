package com.platform.mcp.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * CR-041: Aimbase Agent Registry REST 클라이언트.
 * JDK HttpClient 사용 — 추가 HTTP 라이브러리 불필요.
 */
public class AimbaseRegistrationClient {

    private static final Logger log = LoggerFactory.getLogger(AimbaseRegistrationClient.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final String aimbaseBaseUrl;
    private final String apiKey;
    private final HttpClient httpClient;

    public AimbaseRegistrationClient(String aimbaseBaseUrl, String apiKey) {
        this.aimbaseBaseUrl = aimbaseBaseUrl.endsWith("/")
                ? aimbaseBaseUrl.substring(0, aimbaseBaseUrl.length() - 1)
                : aimbaseBaseUrl;
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 에이전트 등록. 성공 시 agentId 반환.
     */
    public String register(String agentName, String publicAddress, int mcpPort,
                           List<String> toolNames, Map<String, Object> metadata) {
        try {
            Map<String, Object> body = Map.of(
                    "agentName", agentName,
                    "publicAddress", publicAddress,
                    "mcpPort", mcpPort,
                    "toolNames", toolNames != null ? toolNames : List.of(),
                    "metadata", metadata != null ? metadata : Map.of()
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(aimbaseBaseUrl + "/api/v1/agents/register"))
                    .header("Content-Type", "application/json")
                    .header("X-Api-Key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 201 || response.statusCode() == 200) {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) result.get("data");
                String agentId = data != null ? String.valueOf(data.get("id")) : null;
                log.info("Agent registered successfully: id={}, name={}", agentId, agentName);
                return agentId;
            } else {
                log.error("Agent registration failed: status={}, body={}", response.statusCode(), response.body());
                throw new RuntimeException("Registration failed with status " + response.statusCode());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Agent registration failed", e);
        }
    }

    /**
     * 에이전트 해제.
     */
    public void deregister(String agentId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(aimbaseBaseUrl + "/api/v1/agents/" + agentId))
                    .header("X-Api-Key", apiKey)
                    .DELETE()
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 204 || response.statusCode() == 200) {
                log.info("Agent deregistered: id={}", agentId);
            } else {
                log.warn("Agent deregistration returned status {}: {}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.warn("Agent deregistration failed for id={}: {}", agentId, e.getMessage());
        }
    }

    /**
     * 하트비트 전송.
     */
    public void heartbeat(String agentId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(aimbaseBaseUrl + "/api/v1/agents/" + agentId + "/heartbeat"))
                    .header("X-Api-Key", apiKey)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(5))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Heartbeat returned status {}", response.statusCode());
            }
        } catch (Exception e) {
            log.warn("Heartbeat failed for agent {}: {}", agentId, e.getMessage());
        }
    }
}
