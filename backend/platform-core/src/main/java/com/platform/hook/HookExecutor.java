package com.platform.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.domain.HookDefinitionEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * CR-030 PRD-191: 훅 실행기.
 *
 * Internal 훅: Spring Bean (HookCallback) 동기 호출.
 * External 훅: HTTP POST 비동기 호출 + timeout.
 * 실행 실패 시 PASSTHROUGH 폴백 (fail-open).
 */
@Component
public class HookExecutor {

    private static final Logger log = LoggerFactory.getLogger(HookExecutor.class);

    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public HookExecutor(ApplicationContext applicationContext, ObjectMapper objectMapper) {
        this.applicationContext = applicationContext;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    /**
     * 훅 정의에 따라 실행하고 결과를 반환.
     * 실패 시 PASSTHROUGH (fail-open).
     */
    public HookOutput execute(HookDefinitionEntity definition, HookInput input) {
        try {
            if ("INTERNAL".equals(definition.getTargetType())) {
                return executeInternal(definition, input);
            } else {
                return executeExternal(definition, input);
            }
        } catch (Exception e) {
            log.warn("Hook execution failed (fail-open): hook={}, event={}, error={}",
                    definition.getId(), definition.getEvent(), e.getMessage());
            return HookOutput.PASSTHROUGH;
        }
    }

    /**
     * Internal: Spring Bean 이름으로 HookCallback 조회 후 동기 호출.
     */
    private HookOutput executeInternal(HookDefinitionEntity definition, HookInput input) {
        String beanName = definition.getTarget();
        try {
            HookCallback callback = applicationContext.getBean(beanName, HookCallback.class);
            HookOutput output = callback.handle(input);
            if (output == null) {
                return HookOutput.PASSTHROUGH;
            }
            log.debug("Internal hook executed: bean={}, decision={}", beanName, output.decision());
            return output;
        } catch (org.springframework.beans.factory.NoSuchBeanDefinitionException e) {
            log.warn("Internal hook bean not found: {} (hook={})", beanName, definition.getId());
            return HookOutput.PASSTHROUGH;
        }
    }

    /**
     * External: HTTP POST 비동기 호출 + timeout.
     * 요청 본문: HookInput JSON, 응답 본문: HookOutput JSON.
     */
    private HookOutput executeExternal(HookDefinitionEntity definition, HookInput input) {
        String url = definition.getTarget();
        int timeoutMs = definition.getTimeoutMs();

        try {
            String requestBody = objectMapper.writeValueAsString(input);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .build();

            CompletableFuture<HttpResponse<String>> future = httpClient.sendAsync(
                    request, HttpResponse.BodyHandlers.ofString());

            HttpResponse<String> response = future.get(timeoutMs, TimeUnit.MILLISECONDS);

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                HookOutput output = parseExternalResponse(response.body());
                log.debug("External hook executed: url={}, status={}, decision={}",
                        url, response.statusCode(), output.decision());
                return output;
            } else {
                log.warn("External hook returned non-2xx: url={}, status={}", url, response.statusCode());
                return HookOutput.PASSTHROUGH;
            }
        } catch (Exception e) {
            log.warn("External hook failed (timeout/network): url={}, error={}", url, e.getMessage());
            return HookOutput.PASSTHROUGH;
        }
    }

    @SuppressWarnings("unchecked")
    private HookOutput parseExternalResponse(String body) {
        try {
            Map<String, Object> map = objectMapper.readValue(body, Map.class);
            String decisionStr = (String) map.getOrDefault("decision", "PASSTHROUGH");
            HookDecision decision = HookDecision.valueOf(decisionStr.toUpperCase());
            Map<String, Object> updatedInput = (Map<String, Object>) map.get("updatedInput");
            Map<String, Object> metadata = (Map<String, Object>) map.getOrDefault("metadata", Map.of());
            return new HookOutput(decision, updatedInput, metadata);
        } catch (Exception e) {
            log.warn("Failed to parse external hook response: {}", e.getMessage());
            return HookOutput.PASSTHROUGH;
        }
    }
}
