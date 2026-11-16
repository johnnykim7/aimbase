package com.platform.clitoken;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CR-196 BIZ-284: bp-notification 발송 API 로 만료 알림 한 통을 보낸다. 주소와 키는 내장 도구 send_notification 과 같은 값이다.
 * 키가 없으면 보낸 척하지 않고 실패로 돌려, 보낸 기록이 남지 않게 한다.
 */
@Component
public class CliTokenNotifier {

    private static final Logger log = LoggerFactory.getLogger(CliTokenNotifier.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String apiKey;
    private final Duration timeout;
    private final HttpClient http;

    public CliTokenNotifier(@Value("${notification.base-url:http://localhost:8089}") String baseUrl,
                            @Value("${notification.api-key:}") String apiKey,
                            @Value("${aimbase.runner.token-expiry.send-timeout:10s}") Duration timeout) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public boolean sendEmail(String templateCode, Map<String, Object> variables, String email) {
        return send("/api/v1/messages/email", templateCode, variables, "email", email);
    }

    public boolean sendSms(String templateCode, Map<String, Object> variables, String phone) {
        return send("/api/v1/messages/sms", templateCode, variables, "phoneNumber", phone);
    }

    private boolean send(String path, String templateCode, Map<String, Object> variables, String targetField,
                         String target) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[CR-196] notification.api-key 가 없어 만료 알림을 보내지 않음: template={}", templateCode);
            return false;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("templateCode", templateCode);
        body.put("variables", variables);
        body.put(targetField, target);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .header("Content-Type", "application/json")
                    .header("X-API-Key", apiKey)
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("[CR-196] 만료 알림 발송 응답 {}: path={}, template={}", resp.statusCode(), path, templateCode);
                return false;
            }
            return true;
        } catch (IOException e) {
            log.warn("[CR-196] 만료 알림 발송 실패: path={}, {}", path, e.getClass().getSimpleName());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
