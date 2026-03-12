package com.platform.policy;

import com.platform.action.model.ActionRequest;
import com.platform.monitoring.PlatformMetrics;
import com.platform.policy.model.PolicyResult;
import com.platform.policy.model.TriggeredPolicy;
import com.platform.repository.PolicyRepository;
import com.platform.domain.PolicyEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Policy Engine — 활성화된 정책을 우선순위 순으로 평가.
 *
 * 지원 규칙 타입:
 * - DENY            → 즉시 거부
 * - REQUIRE_APPROVAL → 승인 요구
 * - LOG             → 감사 로그 기록 후 ALLOW
 * - TRANSFORM       → Presidio MCP(우선) 또는 PIIMasker(폴백)로 PII 마스킹 후 ALLOW
 * - RATE_LIMIT      → 슬라이딩 윈도우 초과 시 DENY
 * - ALLOW           → 명시적 허용
 *
 * 각 규칙에 `condition` 필드가 있으면 SpEL 표현식으로 평가;
 * false이면 해당 규칙을 스킵.
 */
@Component
public class PolicyEngine {

    private static final Logger log = LoggerFactory.getLogger(PolicyEngine.class);

    private static final int DEFAULT_RATE_LIMIT_MAX = 100;
    private static final long DEFAULT_RATE_LIMIT_WINDOW_MS = 60_000L;

    private final PolicyRepository policyRepository;
    private final AuditLogger auditLogger;
    private final ObjectMapper objectMapper;
    private final PIIMasker piiMasker;
    private final MCPSafetyClient mcpSafetyClient;
    private final RateLimiter rateLimiter;
    private final PlatformMetrics platformMetrics;

    public PolicyEngine(PolicyRepository policyRepository,
                        AuditLogger auditLogger,
                        ObjectMapper objectMapper,
                        PIIMasker piiMasker,
                        MCPSafetyClient mcpSafetyClient,
                        RateLimiter rateLimiter,
                        PlatformMetrics platformMetrics) {
        this.policyRepository = policyRepository;
        this.auditLogger = auditLogger;
        this.objectMapper = objectMapper;
        this.piiMasker = piiMasker;
        this.mcpSafetyClient = mcpSafetyClient;
        this.rateLimiter = rateLimiter;
        this.platformMetrics = platformMetrics;
    }

    /**
     * ActionRequest에 대해 활성화된 모든 Policy를 priority 순으로 평가.
     */
    public PolicyResult evaluate(ActionRequest request) {
        List<PolicyEntity> activePolicies = policyRepository.findByIsActiveTrueOrderByPriorityDesc();

        List<TriggeredPolicy> triggered = new ArrayList<>();
        Map<String, Object> transforms = new LinkedHashMap<>();

        for (PolicyEntity policyEntity : activePolicies) {
            if (!matches(policyEntity, request)) continue;

            List<Map<String, Object>> rules = policyEntity.getRules();
            for (int i = 0; i < rules.size(); i++) {
                Map<String, Object> rule = rules.get(i);
                String type = (String) rule.get("type");

                // SpEL condition 평가 — false면 이 rule 스킵
                if (!evaluateCondition(rule, request)) {
                    log.debug("Policy '{}' rule[{}] condition not met — skipping", policyEntity.getId(), i);
                    continue;
                }

                PolicyResult.PolicyAction action = evaluateRule(type, rule, request, transforms);
                triggered.add(new TriggeredPolicy(policyEntity.getId(), i, action.name()));

                if (action == PolicyResult.PolicyAction.DENY) {
                    String reason = (String) rule.getOrDefault("message",
                            "Policy denied: " + policyEntity.getName());
                    log.info("Policy DENY: {} rule[{}] for intent '{}'", policyEntity.getId(), i, request.intent());
                    // RATE_LIMIT DENY vs 일반 DENY 구분
                    String violationType = "RATE_LIMIT".equalsIgnoreCase(type) ? "rate_limit" : "deny";
                    platformMetrics.recordPolicyViolation(violationType);
                    return new PolicyResult(false, PolicyResult.PolicyAction.DENY, triggered, transforms, reason);
                }
                if (action == PolicyResult.PolicyAction.REQUIRE_APPROVAL) {
                    log.info("Policy REQUIRE_APPROVAL: {} rule[{}] for intent '{}'",
                            policyEntity.getId(), i, request.intent());
                    return new PolicyResult(true, PolicyResult.PolicyAction.REQUIRE_APPROVAL,
                            triggered, transforms, null);
                }
            }
        }

        return new PolicyResult(true, PolicyResult.PolicyAction.ALLOW, triggered,
                transforms.isEmpty() ? null : transforms, null);
    }

    // ─── 매칭 ─────────────────────────────────────────────────────────────

    private boolean matches(PolicyEntity policy, ActionRequest request) {
        Map<String, Object> matchRules = policy.getMatchRules();
        if (matchRules == null || matchRules.isEmpty()) return true;

        if (matchRules.containsKey("intents")) {
            @SuppressWarnings("unchecked")
            List<String> intents = (List<String>) matchRules.get("intents");
            if (!intents.isEmpty() && !intents.contains(request.intent())) return false;
        }

        if (matchRules.containsKey("adapters")) {
            @SuppressWarnings("unchecked")
            List<String> adapters = (List<String>) matchRules.get("adapters");
            if (!adapters.isEmpty()) {
                boolean anyMatch = request.targets().stream()
                        .anyMatch(t -> adapters.contains(t.adapter()));
                if (!anyMatch) return false;
            }
        }

        return true;
    }

    // ─── 규칙 평가 ────────────────────────────────────────────────────────

    /**
     * 단일 규칙 평가. TRANSFORM 결과는 transforms Map에 누적.
     */
    private PolicyResult.PolicyAction evaluateRule(String type,
                                                    Map<String, Object> rule,
                                                    ActionRequest request,
                                                    Map<String, Object> transforms) {
        if (type == null) return PolicyResult.PolicyAction.ALLOW;

        return switch (type.toUpperCase()) {
            case "DENY" -> PolicyResult.PolicyAction.DENY;

            case "REQUIRE_APPROVAL" -> PolicyResult.PolicyAction.REQUIRE_APPROVAL;

            case "LOG" -> {
                String sessionId = request.metadata() != null ? request.metadata().sessionId() : null;
                String userId = request.metadata() != null ? request.metadata().userId() : null;
                String message = (String) rule.getOrDefault("message", "Policy LOG triggered");
                auditLogger.log("policy_log", request.intent(), userId, sessionId,
                        Map.of("message", message, "intent", request.intent()), null);
                log.info("Policy LOG: intent='{}' — {}", request.intent(), message);
                yield PolicyResult.PolicyAction.ALLOW;
            }

            case "TRANSFORM" -> {
                // PII 마스킹 — Presidio MCP 우선, 폴백 시 Java PIIMasker
                Map<String, Object> originalData = request.payload() != null && request.payload().data() != null
                        ? request.payload().data() : Map.of();

                if (mcpSafetyClient.isAvailable()) {
                    try {
                        Map<String, Object> maskedResult = maskViaMCP(originalData);
                        transforms.put("masked_payload", maskedResult);
                        log.debug("Policy TRANSFORM: Presidio PII masking applied for intent '{}'", request.intent());
                    } catch (Exception e) {
                        log.warn("Presidio MCP masking failed, falling back to regex PIIMasker: {}", e.getMessage());
                        transforms.put("masked_payload", piiMasker.maskMap(originalData));
                    }
                } else {
                    transforms.put("masked_payload", piiMasker.maskMap(originalData));
                    log.debug("Policy TRANSFORM: regex PII masking applied for intent '{}'", request.intent());
                }
                yield PolicyResult.PolicyAction.ALLOW;
            }

            case "RATE_LIMIT" -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> config = rule.get("config") instanceof Map
                        ? (Map<String, Object>) rule.get("config") : Map.of();
                int maxRequests = config.get("max_requests") instanceof Number n
                        ? n.intValue() : DEFAULT_RATE_LIMIT_MAX;
                long windowMs = config.get("window_ms") instanceof Number n
                        ? n.longValue() : DEFAULT_RATE_LIMIT_WINDOW_MS;

                String sessionId = request.metadata() != null ? request.metadata().sessionId() : "unknown";
                String rateLimitKey = sessionId + ":" + request.intent();

                if (!rateLimiter.isAllowed(rateLimitKey, maxRequests, windowMs)) {
                    String msg = (String) rule.getOrDefault("message",
                            "Rate limit exceeded: " + maxRequests + " req/" + (windowMs / 1000) + "s");
                    log.warn("Policy RATE_LIMIT exceeded: key='{}' limit={}/{}", rateLimitKey, maxRequests, windowMs);
                    transforms.put("rate_limit_exceeded", msg);
                    yield PolicyResult.PolicyAction.DENY;
                }
                yield PolicyResult.PolicyAction.ALLOW;
            }

            default -> PolicyResult.PolicyAction.ALLOW;
        };
    }

    // ─── MCP PII 마스킹 ─────────────────────────────────────────────────

    /**
     * Map 내 String 값을 Presidio MCP로 마스킹. 중첩 Map 재귀 처리.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> maskViaMCP(Map<String, Object> data) {
        Map<String, Object> masked = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String text) {
                Map<String, Object> result = mcpSafetyClient.maskPii(text);
                masked.put(entry.getKey(), result.get("masked_text"));
            } else if (value instanceof Map) {
                masked.put(entry.getKey(), maskViaMCP((Map<String, Object>) value));
            } else {
                masked.put(entry.getKey(), value);
            }
        }
        return masked;
    }

    // ─── SpEL 조건 평가 ───────────────────────────────────────────────────

    /**
     * 규칙에 `condition` 필드가 있으면 SpEL 표현식으로 평가.
     * condition이 없거나 blank면 true 반환 (규칙 항상 적용).
     */
    private boolean evaluateCondition(Map<String, Object> rule, ActionRequest request) {
        Object condObj = rule.get("condition");
        if (!(condObj instanceof String condition) || condition.isBlank()) {
            return true;
        }

        try {
            ExpressionParser parser = new SpelExpressionParser();
            EvaluationContext ctx = new StandardEvaluationContext();
            ctx.setVariable("intent", request.intent());
            ctx.setVariable("sessionId", request.metadata() != null ? request.metadata().sessionId() : null);
            ctx.setVariable("userId", request.metadata() != null ? request.metadata().userId() : null);
            ctx.setVariable("adapter", request.targets() != null && !request.targets().isEmpty()
                    ? request.targets().get(0).adapter() : null);

            Boolean result = parser.parseExpression(condition).getValue(ctx, Boolean.class);
            return result != null && result;
        } catch (Exception e) {
            log.warn("SpEL condition evaluation failed for '{}': {} — applying rule (fail-open)", condition, e.getMessage());
            return true;
        }
    }
}
