package com.platform.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 워크플로우 스텝 실행 컨텍스트.
 * 이전 스텝 결과와 입력 데이터를 보관하며, {{...}} 변수 치환을 제공.
 */
public record StepContext(
        String workflowRunId,
        String workflowId,
        String sessionId,
        Map<String, Object> inputData,
        Map<String, Object> stepResults  // {"stepId": {"output": ..., ...}}
) {

    private static final Logger log = LoggerFactory.getLogger(StepContext.class);
    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\{\\{([^}]+)}}");

    /**
     * 템플릿 문자열에서 {{...}} 변수를 실제 값으로 치환.
     *
     * 지원 참조 형식:
     * - {{input.key}}  → inputData.get("key")
     * - {{stepId.field}} → stepResults.get("stepId").get("field")
     *
     * @param template 변수 참조를 포함할 수 있는 문자열 (null이면 null 반환)
     * @return 치환된 문자열
     */
    public String resolve(String template) {
        if (template == null) return null;

        StringBuffer sb = new StringBuffer();
        Matcher matcher = TEMPLATE_PATTERN.matcher(template);

        while (matcher.find()) {
            String ref = matcher.group(1).trim();
            String value = resolveRef(ref);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Map 내 String 값들에 대해 변수 치환 수행.
     * 중첩 Map은 재귀적으로 처리.
     *
     * @param config 원본 설정 Map
     * @return 변수가 치환된 새 Map
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> resolveMap(Map<String, Object> config) {
        if (config == null) return Map.of();

        Map<String, Object> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String s) {
                resolved.put(entry.getKey(), resolve(s));
            } else if (value instanceof Map) {
                resolved.put(entry.getKey(), resolveMap((Map<String, Object>) value));
            } else {
                resolved.put(entry.getKey(), value);
            }
        }
        return resolved;
    }

    // ─── 내부 변수 참조 해석 ──────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String resolveRef(String ref) {
        int dot = ref.indexOf('.');
        if (dot < 0) return "{{" + ref + "}}"; // 형식 불일치 → 원문 유지

        String namespace = ref.substring(0, dot);
        String key = ref.substring(dot + 1);

        if ("input".equals(namespace)) {
            Object val = inputData != null ? inputData.get(key) : null;
            // 폴백: 호출 측이 {"input": {"key": "..."}} 형태로 이중 래핑한 경우 처리
            if (val == null && inputData != null && inputData.get("input") instanceof Map nestedInput) {
                val = nestedInput.get(key);
                if (val != null) {
                    log.warn("Template '{{input.{}}}': resolved via nested 'input' wrapper (caller should send flat structure)", key);
                }
            }
            if (val == null) {
                log.warn("Template variable '{{input.{}}}' resolved to null. inputData keys: {}",
                        key, inputData != null ? inputData.keySet() : "null");
            }
            return val != null ? val.toString() : "";
        }

        // stepId.field 참조
        Object stepResult = stepResults != null ? stepResults.get(namespace) : null;
        if (stepResult instanceof Map stepMap) {
            Object val = stepMap.get(key);
            return val != null ? val.toString() : "";
        }

        return ""; // 참조 불가 → 빈 문자열
    }

    /** mutable stepResults 복사본으로 새 컨텍스트 생성 (스텝 결과 추가 후 반환) */
    public StepContext withStepResult(String stepId, Map<String, Object> result) {
        Map<String, Object> newResults = new LinkedHashMap<>(stepResults != null ? stepResults : Map.of());
        newResults.put(stepId, result);
        return new StepContext(workflowRunId, workflowId, sessionId, inputData, newResults);
    }
}
