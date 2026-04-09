package com.platform.tool.builtin;

import com.platform.hook.HookDispatcher;
import com.platform.hook.HookEvent;
import com.platform.hook.HookInput;
import com.platform.tool.model.UnifiedToolDef;
import com.platform.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 알림 발송 내장 도구 (CR-019).
 *
 * bp-notification 서비스(port 8089)를 통해 이메일/SMS/푸시 알림 발송.
 * 템플릿 코드 + 변수 기반 또는 직접 내용 전송.
 */
@Component
public class NotificationTool implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(NotificationTool.class);

    @Value("${notification.base-url:http://localhost:8089}")
    private String notificationBaseUrl;

    @Value("${notification.api-key:}")
    private String notificationApiKey;

    private final HookDispatcher hookDispatcher;
    private final RestTemplate restTemplate = new RestTemplate();

    public NotificationTool(HookDispatcher hookDispatcher) {
        this.hookDispatcher = hookDispatcher;
    }

    private static final UnifiedToolDef DEFINITION = new UnifiedToolDef(
            "send_notification",
            "이메일, SMS, 푸시 알림을 발송합니다. bp-notification 서비스를 통해 실제 발송됩니다.",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "channel", Map.of(
                                    "type", "string",
                                    "enum", List.of("email", "sms", "push"),
                                    "description", "알림 채널 (email, sms, push)"
                            ),
                            "target", Map.of(
                                    "type", "string",
                                    "description", "수신 대상 (이메일 주소 또는 전화번호)"
                            ),
                            "template_code", Map.of(
                                    "type", "string",
                                    "description", "bp-notification 템플릿 코드 (없으면 기본 템플릿 사용)"
                            ),
                            "subject", Map.of(
                                    "type", "string",
                                    "description", "알림 제목 (이메일용)"
                            ),
                            "body", Map.of(
                                    "type", "string",
                                    "description", "알림 본문"
                            ),
                            "variables", Map.of(
                                    "type", "object",
                                    "description", "템플릿 변수 (key-value)"
                            )
                    ),
                    "required", List.of("channel", "target", "body")
            )
    );

    @Override
    public UnifiedToolDef getDefinition() {
        return DEFINITION;
    }

    @Override
    public String execute(Map<String, Object> input) {
        String channel = String.valueOf(input.getOrDefault("channel", ""));
        String target = String.valueOf(input.getOrDefault("target", ""));
        String templateCode = String.valueOf(input.getOrDefault("template_code", "default"));
        String subject = String.valueOf(input.getOrDefault("subject", ""));
        String body = String.valueOf(input.getOrDefault("body", ""));

        @SuppressWarnings("unchecked")
        Map<String, String> variables = input.get("variables") instanceof Map
                ? (Map<String, String>) input.get("variables")
                : new HashMap<>();

        // body를 변수로 포함 (템플릿에서 {{body}} 사용 가능)
        if (!body.isEmpty()) {
            variables.putIfAbsent("body", body);
        }
        if (!subject.isEmpty()) {
            variables.putIfAbsent("subject", subject);
        }

        log.info("send_notification: channel={}, target={}, template={}", channel, target, templateCode);

        // CR-034: NOTIFICATION 훅 발행
        try {
            hookDispatcher.dispatch(HookEvent.NOTIFICATION,
                    HookInput.of(HookEvent.NOTIFICATION, null,
                            Map.of("channel", channel, "target", target, "templateCode", templateCode),
                            Map.of()));
        } catch (Exception ignored) {}

        if (notificationApiKey.isBlank()) {
            log.warn("notification.api-key not configured, returning simulated success");
            return "{\"success\": true, \"channel\": \"" + channel
                    + "\", \"target\": \"" + jsonEscape(target)
                    + "\", \"message\": \"Notification simulated (no API key configured)\"}";
        }

        try {
            String endpoint;
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("templateCode", templateCode);
            requestBody.put("variables", variables);

            switch (channel.toLowerCase()) {
                case "email" -> {
                    endpoint = notificationBaseUrl + "/api/v1/messages/email";
                    requestBody.put("email", target);
                }
                case "sms" -> {
                    endpoint = notificationBaseUrl + "/api/v1/messages/sms";
                    requestBody.put("phoneNumber", target);
                }
                case "push" -> {
                    endpoint = notificationBaseUrl + "/api/v1/messages/push";
                    requestBody.put("deviceToken", target);
                }
                default -> {
                    return "{\"success\": false, \"error\": \"Unknown channel: " + channel + "\"}";
                }
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-API-Key", notificationApiKey);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.exchange(endpoint, HttpMethod.POST, entity, String.class);

            log.info("send_notification completed: status={}", response.getStatusCode());

            return "{\"success\": true, \"channel\": \"" + channel
                    + "\", \"target\": \"" + jsonEscape(target)
                    + "\", \"status\": " + response.getStatusCode().value()
                    + ", \"response\": " + (response.getBody() != null ? response.getBody() : "null") + "}";

        } catch (Exception e) {
            log.error("send_notification failed: {}", e.getMessage());
            return "{\"success\": false, \"error\": " + jsonEscape(e.getMessage()) + "}";
        }
    }

    private String jsonEscape(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }
}
