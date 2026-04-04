package com.platform.tool;

import com.platform.llm.model.ToolCall;
import com.platform.llm.model.UnifiedToolDef;
import com.platform.monitoring.PlatformMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 모든 도구(built-in + MCP)를 관리하는 중앙 레지스트리.
 *
 * - built-in 도구: @Component로 자동 감지 후 생성자 주입으로 등록
 * - MCP 도구: MCPServerManager가 discover() 시 동적으로 register()
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, ToolExecutor> executors = new ConcurrentHashMap<>();
    private final PlatformMetrics platformMetrics;

    /**
     * 생성자 주입으로 built-in 도구 등록.
     * Spring이 ToolExecutor 구현체를 모두 수집하여 주입.
     */
    public ToolRegistry(List<ToolExecutor> builtins, PlatformMetrics platformMetrics) {
        this.platformMetrics = platformMetrics;
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
        return executors.values().stream()
                .map(ToolExecutor::getDefinition)
                .filter(def -> filter.isToolAllowed(def.name()))
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

    public boolean hasTools() {
        return !executors.isEmpty();
    }
}
