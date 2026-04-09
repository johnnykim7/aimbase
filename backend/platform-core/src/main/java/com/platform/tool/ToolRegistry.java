package com.platform.tool;

import com.platform.llm.model.ToolCall;
import com.platform.tool.model.UnifiedToolDef;
import com.platform.monitoring.PlatformMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 모든 도구(built-in + MCP)를 관리하는 중앙 레지스트리.
 *
 * - built-in 도구: @PostConstruct에서 지연 등록 (순환 참조 방지)
 * - MCP 도구: MCPServerManager가 discover() 시 동적으로 register()
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, ToolExecutor> executors = new ConcurrentHashMap<>();
    private final PlatformMetrics platformMetrics;
    private final List<ToolExecutor> builtins;

    public ToolRegistry(@Lazy List<ToolExecutor> builtins, PlatformMetrics platformMetrics) {
        this.platformMetrics = platformMetrics;
        this.builtins = builtins;
    }

    @EventListener(ApplicationReadyEvent.class)
    void registerBuiltins() {
        builtins.forEach(this::register);
        log.info("ToolRegistry initialized with {} built-in tool(s): {}",
                builtins.size(),
                builtins.stream().map(t -> t.getDefinition().name()).toList());
    }

    /** MCP 도구 등록 (MCPServerManager에서 호출) */
    public void register(ToolExecutor executor) {
        String name = executor.getDefinition().name();
        executors.put(name, executor);
        log.debug("Registered tool: {}", name);
    }

    /** MCP 도구 제거 (서버 disconnect 시) */
    public void unregister(String toolName) {
        executors.remove(toolName);
        log.debug("Unregistered tool: {}", toolName);
    }

    /** CR-034: 등록된 도구 이름 집합 반환 */
    public java.util.Set<String> getRegisteredToolNames() {
        return java.util.Collections.unmodifiableSet(executors.keySet());
    }

    /** LLMRequest.tools에 전달할 모든 도구 정의 */
    public List<UnifiedToolDef> getToolDefs() {
        return executors.values().stream()
                .map(ToolExecutor::getDefinition)
                .toList();
    }

    /**
     * 필터 컨텍스트에 따라 도구 정의를 필터링하여 반환.
     * filter가 null이면 전체 도구 반환 (하위호환).
     *
     * @param filter 도구 필터 컨텍스트
     * @return 필터링된 도구 정의 목록
     */
    public List<UnifiedToolDef> getToolDefs(ToolFilterContext filter) {
        if (filter == null) {
            return getToolDefs();
        }
        return executors.entrySet().stream()
                .filter(entry -> {
                    String name = entry.getKey();
                    ToolExecutor executor = entry.getValue();

                    // 기존: 이름 기반 allow/exclude
                    if (!filter.isToolAllowed(name)) return false;

                    // CR-029: capability/permission/readOnly 필터링
                    if (executor instanceof EnhancedToolExecutor enhanced) {
                        ToolContractMeta meta = enhanced.getContractMeta();

                        // readOnlyMode: readOnly 도구만 허용
                        if (Boolean.TRUE.equals(filter.readOnlyMode()) && !meta.readOnly()) {
                            return false;
                        }

                        // maxPermission: 도구의 permissionLevel이 최대 허용 수준 이내
                        if (filter.maxPermission() != null) {
                            if (meta.permissionLevel().ordinal() > filter.maxPermission().ordinal()) {
                                return false;
                            }
                        }

                        // requiredCapabilities: 도구가 요구 capability를 모두 지원
                        if (filter.requiredCapabilities() != null && !filter.requiredCapabilities().isEmpty()) {
                            if (meta.capabilities() == null || !meta.capabilities().containsAll(filter.requiredCapabilities())) {
                                return false;
                            }
                        }
                    }
                    return true;
                })
                .map(entry -> entry.getValue().getDefinition())
                .toList();
    }

    /**
     * ToolCall 실행.
     * @throws IllegalArgumentException 등록되지 않은 도구 이름인 경우
     */
    public String execute(ToolCall call) {
        ToolExecutor executor = executors.get(call.name());
        if (executor == null) {
            log.error("No executor found for tool: {}", call.name());
            return "오류: 알 수 없는 도구 '" + call.name() + "'";
        }
        try {
            String result = executor.execute(call.input());
            log.debug("Tool '{}' executed successfully", call.name());
            platformMetrics.recordToolExecution(call.name(), true);
            return result;
        } catch (Exception e) {
            log.error("Tool '{}' execution failed: {}", call.name(), e.getMessage());
            platformMetrics.recordToolExecution(call.name(), false);
            return "도구 실행 오류: " + e.getMessage();
        }
    }

    /**
     * CR-029: 컨텍스트 기반 도구 실행.
     * EnhancedToolExecutor이면 validateInput → execute(Map, ToolContext),
     * 기존 ToolExecutor이면 execute(Map) → ToolResult로 래핑.
     */
    public ToolResult execute(ToolCall call, ToolContext ctx) {
        ToolExecutor executor = executors.get(call.name());
        if (executor == null) {
            log.error("No executor found for tool: {}", call.name());
            return ToolResult.error("알 수 없는 도구: " + call.name());
        }

        long start = System.currentTimeMillis();
        try {
            if (executor instanceof EnhancedToolExecutor enhanced) {
                // C1: Permission 체크 — ctx.permissionLevel이 도구 요구 수준 미충족 시 거부
                // PRD-196: AUTO는 ToolCallHandler에서 미리 해소되어야 함.
                //          만약 AUTO가 여기까지 오면 fail-secure로 READ_ONLY 취급.
                ToolContractMeta meta = enhanced.getContractMeta();
                PermissionLevel effectiveLevel = (ctx != null && ctx.permissionLevel() != null)
                        ? ctx.permissionLevel() : PermissionLevel.READ_ONLY;
                if (effectiveLevel == PermissionLevel.AUTO) {
                    log.warn("AUTO permission reached C1 check unresolved — fail-secure to READ_ONLY");
                    effectiveLevel = PermissionLevel.READ_ONLY;
                }
                if (meta != null && meta.permissionLevel().ordinal() > effectiveLevel.ordinal()) {
                    String reason = String.format("tool '%s' requires %s but context allows %s",
                            call.name(), meta.permissionLevel(), effectiveLevel);
                    log.warn("C1 permission denied: {}", reason);
                    platformMetrics.recordToolExecution(call.name(), false);
                    return ToolResult.denied(reason);
                }

                // 입력 검증
                ValidationResult validation = enhanced.validateInput(call.input(), ctx);
                if (!validation.valid()) {
                    platformMetrics.recordToolExecution(call.name(), false);
                    return ToolResult.error("입력 검증 실패: " + validation.message());
                }
                // 확장 실행
                ToolResult result = enhanced.execute(call.input(), ctx);
                platformMetrics.recordToolExecution(call.name(), result.success());
                return result.withDuration(System.currentTimeMillis() - start);
            } else {
                // 기존 ToolExecutor bridge
                String rawResult = executor.execute(call.input());
                platformMetrics.recordToolExecution(call.name(), true);
                return ToolResult.ok(rawResult, rawResult)
                        .withDuration(System.currentTimeMillis() - start);
            }
        } catch (Exception e) {
            log.error("Tool '{}' execution failed: {}", call.name(), e.getMessage());
            platformMetrics.recordToolExecution(call.name(), false);
            return ToolResult.error("도구 실행 오류: " + e.getMessage())
                    .withDuration(System.currentTimeMillis() - start);
        }
    }

    /**
     * CR-029: EnhancedToolExecutor의 계약 메타 포함한 도구 정의 조회.
     */
    public ToolContractMeta getContractMeta(String toolName) {
        ToolExecutor executor = executors.get(toolName);
        if (executor instanceof EnhancedToolExecutor enhanced) {
            return enhanced.getContractMeta();
        }
        return null;
    }

    /**
     * CR-035 PRD-236: 키워드/태그/스코프 기반 도구 검색.
     * name + description 키워드 매칭 + tags 교집합 필터링.
     */
    public List<Map<String, Object>> searchTools(String query, List<String> tags,
                                                  String scope, int maxResults) {
        String lowerQuery = query != null ? query.toLowerCase() : "";

        return executors.entrySet().stream()
                .filter(entry -> {
                    ToolExecutor executor = entry.getValue();
                    UnifiedToolDef def = executor.getDefinition();

                    // 키워드 매칭 (name + description)
                    if (!lowerQuery.isEmpty()) {
                        boolean nameMatch = def.name().toLowerCase().contains(lowerQuery);
                        boolean descMatch = def.description() != null
                                && def.description().toLowerCase().contains(lowerQuery);
                        if (!nameMatch && !descMatch) return false;
                    }

                    if (executor instanceof EnhancedToolExecutor enhanced) {
                        ToolContractMeta meta = enhanced.getContractMeta();

                        // 태그 필터 (교집합)
                        if (tags != null && !tags.isEmpty() && meta.tags() != null) {
                            boolean anyTagMatch = tags.stream()
                                    .anyMatch(t -> meta.tags().contains(t));
                            if (!anyTagMatch) return false;
                        }

                        // 스코프 필터
                        if (scope != null && !scope.isEmpty()) {
                            try {
                                ToolScope scopeFilter = ToolScope.valueOf(scope.toUpperCase());
                                if (meta.scope() != scopeFilter) return false;
                            } catch (IllegalArgumentException ignored) {}
                        }
                    }
                    return true;
                })
                .limit(maxResults > 0 ? maxResults : 20)
                .map(entry -> {
                    ToolExecutor executor = entry.getValue();
                    UnifiedToolDef def = executor.getDefinition();
                    Map<String, Object> result = new java.util.HashMap<>();
                    result.put("name", def.name());
                    result.put("description", def.description());

                    if (executor instanceof EnhancedToolExecutor enhanced) {
                        ToolContractMeta meta = enhanced.getContractMeta();
                        result.put("scope", meta.scope().name());
                        result.put("permission_level", meta.permissionLevel().name());
                        result.put("tags", meta.tags());
                        result.put("read_only", meta.readOnly());
                    } else {
                        result.put("scope", "BUILTIN");
                        result.put("permission_level", "FULL");
                        result.put("tags", List.of());
                        result.put("read_only", false);
                    }
                    return result;
                })
                .toList();
    }

    public boolean hasTools() {
        return !executors.isEmpty();
    }
}
