package com.platform.workflow.step;

import com.platform.llm.model.ToolCall;
import com.platform.tool.ToolRegistry;
import com.platform.workflow.StepContext;
import com.platform.workflow.model.WorkflowStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * TOOL_CALL 스텝 실행기.
 *
 * config 형식:
 * {
 *   "tool": "calculate",                 // 도구 이름 (ToolRegistry에 등록된 것)
 *   "input": {"expression": "{{s1.output}}"}  // 도구 입력 (변수 치환 지원)
 *   "response_schema": { ... }           // (선택) 도구에 전달 — 도구가 자체적으로 구조화 처리
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
        Map<String, Object> rawInput = inputObj instanceof Map ? new HashMap<>((Map<String, Object>) inputObj) : new HashMap<>();

        // response_schema를 도구 input으로 전달 (도구가 자체적으로 처리)
        Object responseSchema = config.get("response_schema");
        if (responseSchema != null) {
            rawInput.put("response_schema", responseSchema);
        }

        // 에이전트 계정 ID 패스스루 (도구 dispatch 시 _agent_account_id 로 노출)
        String agentAccountId = (String) config.get("agent_account_id");
        if (agentAccountId != null && !agentAccountId.isBlank()) {
            rawInput.put("_agent_account_id", agentAccountId);
        }

        // 변수 치환 — 단일 {{ref}} 참조는 객체 그대로 보존 (Map/List 등을 toString 화 방지).
        // resolveMap 만 쓰면 Map 값이 Java toString("{key=value,...}") 으로 직렬화되어
        // 소비앱 측 JSON 파싱이 깨진다 (CR-087 resolveObject 활용).
        Map<String, Object> resolvedInput = resolveInputPreservingObjects(rawInput, context);

        log.debug("TOOL_CALL step '{}': executing tool '{}'", step.id(), toolName);

        String result = toolRegistry.execute(new ToolCall(null, toolName, resolvedInput));

        log.debug("TOOL_CALL step '{}' completed", step.id());

        String output = result != null ? result : "";

        // ToolRegistry.execute(ToolCall) 는 미인식 도구/실행 예외를 "오류: ..." / "도구 실행 오류: ..." 문자열로 반환한다
        // (String 반환 시그니처라 throw 못 함). 그 결과를 그대로 output 에 담으면
        // WorkflowEngine.executeWithRetry 가 Exception 만 보기 때문에 retry/failed 처리가 안 되고
        // status=completed 가짜 성공으로 끝난다 — 여기서 RuntimeException 으로 승격해서 retry 정책에 태운다.
        if (output.startsWith("오류: ") || output.startsWith("도구 실행 오류: ")) {
            throw new RuntimeException(output);
        }

        // output이 JSON이면 structured_data에도 저장 (LLM_CALL과 동일한 참조 키 지원)
        if (output.startsWith("{") || output.startsWith("[")) {
            return Map.of("output", output, "structured_data", output);
        }
        return Map.of("output", output);
    }

    /**
     * Tool input 변수 치환 — 단일 {{ref}} 참조는 원본 객체를 그대로 유지하고,
     * 혼합 문자열/리터럴은 기존 텍스트 치환 경로를 탄다.
     *
     * <p>{@link StepContext#resolveMap}만 쓰면 Map/List 값이 Java {@code toString}
     * ({@code {key=value, ...}}) 으로 직렬화되어 소비앱 측 JSON 파싱이 깨진다.
     * 중첩 Map 도 재귀 적용.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveInputPreservingObjects(Map<String, Object> raw, StepContext context) {
        if (raw == null) return Map.of();
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String s) {
                Object resolved = context.resolveObject(s);
                // resolveObject 는 "단일 {{ref}} 참조" 일 때만 객체 반환, 아니면 null.
                // 객체로 풀린 경우만 보존, 그 외(혼합 문자열·리터럴)는 기존 텍스트 치환.
                if (resolved != null && !(resolved instanceof String)) {
                    out.put(e.getKey(), resolved);
                } else {
                    out.put(e.getKey(), context.resolve(s));
                }
            } else if (v instanceof Map) {
                out.put(e.getKey(), resolveInputPreservingObjects((Map<String, Object>) v, context));
            } else {
                out.put(e.getKey(), v);
            }
        }
        return out;
    }
}
