package com.platform.workflow.step;

import com.platform.llm.model.ToolCall;
import com.platform.tool.ToolRegistry;
import com.platform.workflow.StepContext;
import com.platform.workflow.model.WorkflowStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * TOOL_CALL 스텝 실행기.
 *
 * config 형식:
 * {
 *   "tool": "calculate",                 // 도구 이름 (ToolRegistry에 등록된 것)
 *   "input": {"expression": "{{s1.output}}"}  // 도구 입력 (변수 치환 지원)
 * }
 */
@Component
public class ToolCallStepExecutor implements StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolCallStepExecutor.class);

    private final ToolRegistry toolRegistry;

    public ToolCallStepExecutor(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @Override
    public WorkflowStep.StepType supports() {
        return WorkflowStep.StepType.TOOL_CALL;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(WorkflowStep step, StepContext context) {
        Map<String, Object> config = step.config();

        String toolName = (String) config.get("tool");
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("TOOL_CALL step '" + step.id() + "' missing 'tool' config");
        }

        Object inputObj = config.get("input");
        Map<String, Object> rawInput = inputObj instanceof Map ? (Map<String, Object>) inputObj : Map.of();

        // 변수 치환
        Map<String, Object> resolvedInput = context.resolveMap(rawInput);

        log.debug("TOOL_CALL step '{}': executing tool '{}'", step.id(), toolName);

        String result = toolRegistry.execute(new ToolCall(null, toolName, resolvedInput));

        log.debug("TOOL_CALL step '{}' completed", step.id());

        return Map.of("output", result != null ? result : "");
    }
}
