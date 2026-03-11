package com.platform.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 플랫폼 핵심 메트릭 수집.
 *
 * Prometheus 엔드포인트: GET /actuator/prometheus
 *
 * 수집 메트릭:
 * - platform.llm.calls.total        {provider, model, status}
 * - platform.llm.latency.seconds    {provider, model}
 * - platform.llm.tokens.input.total {provider, model}
 * - platform.llm.tokens.output.total{provider, model}
 * - platform.workflow.executions.total {status}
 * - platform.policy.violations.total   {type}
 * - platform.tool.executions.total     {tool, status}
 */
@Component
public class PlatformMetrics {

    private final MeterRegistry registry;

    public PlatformMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * LLM 호출 메트릭 기록.
     *
     * @param provider    LLM 제공자 (anthropic, openai, ollama)
     * @param model       모델 ID (claude-sonnet-4-6 등)
     * @param success     성공 여부
     * @param latencyMs   응답 지연 (밀리초)
     * @param inputTokens 입력 토큰 수
     * @param outputTokens 출력 토큰 수
     */
    public void recordLlmCall(String provider, String model, boolean success,
                               long latencyMs, long inputTokens, long outputTokens) {
        String status = success ? "success" : "error";
        String safeProvider = provider != null ? provider : "unknown";
        String safeModel = model != null ? model : "unknown";

        Counter.builder("platform.llm.calls.total")
                .tag("provider", safeProvider)
                .tag("model", safeModel)
                .tag("status", status)
                .register(registry)
                .increment();

        Timer.builder("platform.llm.latency.seconds")
                .tag("provider", safeProvider)
                .tag("model", safeModel)
                .register(registry)
                .record(latencyMs, TimeUnit.MILLISECONDS);

        if (inputTokens > 0) {
            Counter.builder("platform.llm.tokens.input.total")
                    .tag("provider", safeProvider)
                    .tag("model", safeModel)
                    .register(registry)
                    .increment(inputTokens);
        }

        if (outputTokens > 0) {
            Counter.builder("platform.llm.tokens.output.total")
                    .tag("provider", safeProvider)
                    .tag("model", safeModel)
                    .register(registry)
                    .increment(outputTokens);
        }
    }

    /**
     * 워크플로우 실행 완료 메트릭 기록.
     *
     * @param status completed / failed / cancelled
     */
    public void recordWorkflowExecution(String status) {
        Counter.builder("platform.workflow.executions.total")
                .tag("status", status != null ? status : "unknown")
                .register(registry)
                .increment();
    }

    /**
     * 정책 위반 메트릭 기록.
     *
     * @param type deny / rate_limit
     */
    public void recordPolicyViolation(String type) {
        Counter.builder("platform.policy.violations.total")
                .tag("type", type != null ? type : "unknown")
                .register(registry)
                .increment();
    }

    /**
     * 도구 실행 메트릭 기록.
     *
     * @param toolName 도구 이름
     * @param success  성공 여부
     */
    public void recordToolExecution(String toolName, boolean success) {
        Counter.builder("platform.tool.executions.total")
                .tag("tool", toolName != null ? toolName : "unknown")
                .tag("status", success ? "success" : "error")
                .register(registry)
                .increment();
    }
}
