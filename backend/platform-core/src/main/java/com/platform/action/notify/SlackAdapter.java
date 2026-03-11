package com.platform.action.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.action.model.HealthStatus;
import com.platform.action.model.NotifyResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

@Component
public class SlackAdapter implements NotifyAdapter {

    private static final Logger log = LoggerFactory.getLogger(SlackAdapter.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Value("${slack.bot-token:}")
    private String botToken;

    public SlackAdapter(ObjectMapper objectMapper) {
        this.restClient = RestClient.create();
        this.objectMapper = objectMapper;
    }

    @Override
    public String getId() {
        return "slack";
    }

    @Override
    public void connect(Map<String, Object> config) {
        if (config.containsKey("bot_token")) {
            this.botToken = (String) config.get("bot_token");
        }
    }

    @Override
    public void disconnect() {}

    @Override
    public HealthStatus healthCheck() {
        if (botToken == null || botToken.isBlank()) {
            return HealthStatus.unhealthy();
        }
        return HealthStatus.healthy(0);
    }

    @Override
    public NotifyResult publish(String channel, Object event, Map<String, Object> options) {
        if (botToken == null || botToken.isBlank()) {
            return NotifyResult.failure("Slack bot token not configured");
        }
        try {
            String text = event instanceof String s ? s
                    : objectMapper.writeValueAsString(event);

            Map<String, Object> payload = Map.of("channel", channel, "text", text);
            String body = objectMapper.writeValueAsString(payload);

            var response = restClient.post()
                    .uri("https://slack.com/api/chat.postMessage")
                    .header("Authorization", "Bearer " + botToken)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            boolean ok = response != null && Boolean.TRUE.equals(response.get("ok"));
            if (ok) {
                String ts = response.containsKey("ts") ? response.get("ts").toString() : UUID.randomUUID().toString();
                return NotifyResult.success(ts);
            } else {
                String error = response != null ? String.valueOf(response.get("error")) : "unknown";
                return NotifyResult.failure("Slack API error: " + error);
            }
        } catch (Exception e) {
            log.error("Slack publish error to {}: {}", channel, e.getMessage());
            return NotifyResult.failure(e.getMessage());
        }
    }
}
