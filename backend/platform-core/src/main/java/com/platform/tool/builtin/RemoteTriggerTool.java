package com.platform.tool.builtin;

import com.platform.llm.model.ToolCall;
import com.platform.tool.model.UnifiedToolDef;
import com.platform.policy.AuditLogger;
import com.platform.tenant.TenantContext;
import com.platform.tool.*;
import com.platform.workflow.WorkflowEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * CR-038 PRD-247: 이벤트 기반 즉시 트리거.
 * Cron(주기적)과 보완적으로 워크플로우/도구를 즉시 1회 실행한다.
 * BIZ-067: 테넌트당 분당 10회 제한.
 */
@Component
public class RemoteTriggerTool implements EnhancedToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(RemoteTriggerTool.class);
    private static final int MAX_TRIGGERS_PER_MINUTE = 10; // BIZ-067
    private static final String RATE_KEY_PREFIX = "trigger:rate:";

    private final WorkflowEngine workflowEngine;
    private final ToolRegistry toolRegistry;
    private final AuditLogger auditLogger;
    private final RedisTemplate<String, String> redisTemplate;

    public RemoteTriggerTool(WorkflowEngine workflowEngine,
                              ToolRegistry toolRegistry,
                              AuditLogger auditLogger,
                              RedisTemplate<String, String> redisTemplate) {
        this.workflowEngine = workflowEngine;
        this.toolRegistry = toolRegistry;
        this.auditLogger = auditLogger;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "remote_trigger",
                "워크플로우 또는 도구를 즉시 1회 실행합니다 (이벤트 기반 트리거). " +
                        "Cron 스케줄과 달리 즉시 실행이 필요할 때 사용합니다.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "target_type", Map.of("type", "string",
                                        "enum", List.of("WORKFLOW", "TOOL"),
                                        "description", "실행 대상 타입"),
                                "target_id", Map.of("type", "string",
                                        "description", "실행 대상 ID (워크플로우 ID 또는 도구 이름)"),
                                "input", Map.of("type", "object",
                                        "description", "입력 파라미터 (선택)"),
                                "trigger_reason", Map.of("type", "string",
                                        "description", "트리거 사유 (감사 로그에 기록)")
                        ),
                        "required", List.of("target_type", "target_id")
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return new ToolContractMeta(
                "remote_trigger", "1.0", ToolScope.BUILTIN,
                PermissionLevel.RESTRICTED_WRITE,
                false, false, false, true,
                RetryPolicy.NONE,
                List.of("automation", "trigger", "execution"),
                List.of("execute", "trigger")
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        String targetType = (String) input.get("target_type");
        String targetId = (String) input.get("target_id");
        Map<String, Object> inputPayload = (Map<String, Object>) input.get("input");
        String triggerReason = (String) input.getOrDefault("trigger_reason", "manual_trigger");

        if (targetType == null || targetId == null) {
            return ToolResult.error("target_type, target_id는 필수입니다");
        }

        String tenantId = ctx.tenantId();
        if (tenantId == null) {
            return ToolResult.error("테넌트 컨텍스트가 필요합니다");
        }

        // BIZ-067: 분당 10회 제한
        if (!checkRateLimit(tenantId)) {
            return ToolResult.error("분당 트리거 횟수 초과 (최대 " + MAX_TRIGGERS_PER_MINUTE + "회/분)");
        }

        long startTime = System.currentTimeMillis();
        String resultSummary;

        try {
            TenantContext.setTenantId(tenantId);

            if ("WORKFLOW".equalsIgnoreCase(targetType)) {
                workflowEngine.execute(targetId, inputPayload != null ? inputPayload : Map.of(), null);
                resultSummary = "워크플로우 '" + targetId + "' 실행 완료";
            } else if ("TOOL".equalsIgnoreCase(targetType)) {
                var call = new ToolCall("call-trigger-" + System.currentTimeMillis(), targetId,
                        inputPayload != null ? inputPayload : Map.of());
                var toolCtx = ToolContext.minimal(tenantId, ctx.sessionId());
                ToolResult toolResult = toolRegistry.execute(call, toolCtx);
                if (!toolResult.success()) {
                    return ToolResult.error("도구 실행 실패: " + toolResult.summary());
                }
                resultSummary = "도구 '" + targetId + "' 실행 완료: " + toolResult.summary();
            } else {
                return ToolResult.error("지원하지 않는 target_type: " + targetType + " (WORKFLOW/TOOL만 허용)");
            }

            long durationMs = System.currentTimeMillis() - startTime;

            auditLogger.log("REMOTE_TRIGGER", targetId, null, null,
                    Map.of("target_type", targetType, "target_id", targetId,
                            "trigger_reason", triggerReason, "tenantId", tenantId,
                            "duration_ms", durationMs), null);

            return ToolResult.ok(
                    Map.of("triggered", true, "target_type", targetType,
                            "target_id", targetId, "result_summary", resultSummary,
                            "duration_ms", durationMs),
                    resultSummary
            );
        } catch (Exception e) {
            log.error("RemoteTrigger failed: {} {} — {}", targetType, targetId, e.getMessage());
            return ToolResult.error("트리거 실행 실패: " + e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }

    private boolean checkRateLimit(String tenantId) {
        try {
            String key = RATE_KEY_PREFIX + tenantId;
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1) {
                redisTemplate.expire(key, Duration.ofSeconds(60));
            }
            return count == null || count <= MAX_TRIGGERS_PER_MINUTE;
        } catch (Exception e) {
            log.warn("Rate limit check failed, allowing trigger: {}", e.getMessage());
            return true;
        }
    }
}
