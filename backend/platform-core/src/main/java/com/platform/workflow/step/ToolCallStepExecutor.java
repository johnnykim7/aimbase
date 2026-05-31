package com.platform.workflow.step;

import com.platform.llm.model.ToolCall;
import com.platform.tool.ToolRegistry;
import com.platform.workflow.StepContext;
import com.platform.workflow.event.WorkflowRunEventRecorder;
import com.platform.workflow.model.WorkflowStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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
    /** CR-090: workflow_run_events 비동기 기록. null 허용(테스트 편의). */
    private final WorkflowRunEventRecorder eventRecorder;

    /**
     * Spring 생성자 — ObjectProvider 로 옵셔널 주입.
     *
     * <p>{@code @Autowired} 미지정 시 Spring 6 은 다중 public 생성자에서 default 생성자를 찾아
     * NoSuchMethodException 으로 기동 실패한다 (운영 6/1 21:04 사고 사례). 단일 생성자로 정리.
     * 테스트는 {@code null} ObjectProvider 를 그대로 넘기면 된다.
     */
    public ToolCallStepExecutor(ToolRegistry toolRegistry,
                                ObjectProvider<WorkflowRunEventRecorder> eventRecorderProvider) {
        this.toolRegistry = toolRegistry;
        this.eventRecorder = eventRecorderProvider != null ? eventRecorderProvider.getIfAvailable() : null;
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

        // CR-090: TOOL_USE
        UUID runUuid = parseUuid(context.workflowRunId());
        if (eventRecorder != null && runUuid != null) {
            eventRecorder.toolUse(runUuid, step.id(), null, toolName, resolvedInput, null);
        }

        long startMs = System.currentTimeMillis();
        String result;
        try {
            result = toolRegistry.execute(new ToolCall(null, toolName, resolvedInput));
        } catch (RuntimeException ex) {
            // CR-090: TOOL_RESULT (예외 경로 — ToolRegistry 는 보통 String 반환이지만 안전망)
            if (eventRecorder != null && runUuid != null) {
                eventRecorder.toolResult(runUuid, step.id(), null, toolName,
                        System.currentTimeMillis() - startMs, false, ex.getMessage(), 0, null);
            }
            throw ex;
        }
        long durationMs = System.currentTimeMillis() - startMs;

        log.debug("TOOL_CALL step '{}' completed", step.id());

        String output = result != null ? result : "";

        // ToolRegistry.execute(ToolCall) 는 미인식 도구/실행 예외를 "오류: ..." / "도구 실행 오류: ..." 문자열로 반환한다
        // (String 반환 시그니처라 throw 못 함). 그 결과를 그대로 output 에 담으면
        // WorkflowEngine.executeWithRetry 가 Exception 만 보기 때문에 retry/failed 처리가 안 되고
        // status=completed 가짜 성공으로 끝난다 — 여기서 RuntimeException 으로 승격해서 retry 정책에 태운다.
        if (output.startsWith("오류: ") || output.startsWith("도구 실행 오류: ")) {
            // CR-090: TOOL_RESULT (도구 자체 에러 문자열)
            if (eventRecorder != null && runUuid != null) {
                eventRecorder.toolResult(runUuid, step.id(), null, toolName,
                        durationMs, false, output, output.length(), null);
            }
            throw new RuntimeException(output);
        }

        // CR-090: TOOL_RESULT (성공)
        if (eventRecorder != null && runUuid != null) {
            eventRecorder.toolResult(runUuid, step.id(), null, toolName,
                    durationMs, true, null, output.length(), null);
        }

        // output이 JSON이면 structured_data에도 저장 (LLM_CALL과 동일한 참조 키 지원)
        if (output.startsWith("{") || output.startsWith("[")) {
            return Map.of("output", output, "structured_data", output);
        }
        return Map.of("output", output);
    }

    private static UUID parseUuid(String s) {
        try {
            return s != null ? UUID.fromString(s) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
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
