package com.platform.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
        Map<String, Object> stepResults,  // {"stepId": {"output": ..., ...}}
        Map<String, Object> loopData      // CR-055: EVALUATOR_LOOP iteration 변수 ({{loop.*}} 참조용)
) {

    private static final Logger log = LoggerFactory.getLogger(StepContext.class);
    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\{\\{([^}]+)}}");

    /** 기존 호출부 호환용 5-arg 생성자 — loopData=null로 처리 (루프 외부 컨텍스트). */
    public StepContext(String workflowRunId, String workflowId, String sessionId,
                       Map<String, Object> inputData, Map<String, Object> stepResults) {
        this(workflowRunId, workflowId, sessionId, inputData, stepResults, null);
    }

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

    private String resolveRef(String ref) {
        // CR-085 P1: 첫 경로 토큰을 namespace 로, 나머지를 경로로 분리.
        // 하위호환: "ns.key" 1뎁스는 기존 indexOf('.') 분리와 동일 결과
        // (parsePath 첫 토큰=ns, 나머지=key). 점 없는 형식은 기존대로 원문 유지.
        if (ref.indexOf('.') < 0 && ref.indexOf('[') < 0) {
            return "{{" + ref + "}}"; // 형식 불일치 → 원문 유지 (기존 동작 보존)
        }
        List<String> tokens = parsePath(ref);
        if (tokens.isEmpty()) return "{{" + ref + "}}";

        String namespace = tokens.get(0);
        // namespace 자체가 [n] 인덱스면 참조 불가 (기존에도 의미 없던 케이스)
        if (namespace.startsWith("[")) return "";
        // 나머지 경로 토큰 (이미 파싱됨) — traverse 오버로드로 직접 탐색
        List<String> pathTokens = tokens.subList(1, tokens.size());
        // 로그 메시지용 원본 key 문자열 (기존 로그 포맷 보존)
        String key = ref.substring(namespace.length()).replaceFirst("^\\.", "");

        if ("input".equals(namespace)) {
            // CR-085 P1: key 를 중첩 경로(a.b[0].c)로 해석. 1뎁스 키는 첫 토큰만이라 기존과 동일 결과.
            Object val = inputData != null ? traverse(inputData, pathTokens) : null;
            // 폴백: 호출 측이 {"input": {"key": "..."}} 형태로 이중 래핑한 경우 처리
            if (val == null && inputData != null && inputData.get("input") instanceof Map nestedInput) {
                val = traverse(nestedInput, pathTokens);
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

        // CR-055: {{loop.*}} — EVALUATOR_LOOP iteration 내부 변수
        // 루프 외부에서 참조 시 loopData=null이므로 빈 문자열 반환 (기존 누락 변수 처리와 동일)
        if ("loop".equals(namespace)) {
            if (loopData == null) return "";
            Object val = traverse(loopData, pathTokens);  // CR-085 P1: 중첩 경로 지원
            return val != null ? val.toString() : "";
        }

        // stepId.field 참조 — CR-085 P1: field 부분을 중첩 경로로 해석
        Object stepResult = stepResults != null ? stepResults.get(namespace) : null;
        if (stepResult != null) {
            Object val = traverse(stepResult, pathTokens);
            return val != null ? val.toString() : "";
        }

        return ""; // 참조 불가 → 빈 문자열
    }

    /**
     * CR-085 P1: 점/대괄호 경로(a.b[0].c)를 따라 Map/List 를 재귀 탐색.
     *
     * <p><b>하위호환 절대 규칙</b>: 단일 토큰 경로(점/대괄호 없음)는 {@code root.get(path)} 와
     * 정확히 동일한 결과. 중간에 Map/List 가 아니거나 인덱스 범위를 벗어나면 {@code null} 반환 →
     * 호출부의 기존 "빈 문자열 폴백" 동작이 그대로 적용된다. 즉 표현력만 확장, 동작 변화 0.
     *
     * @param root        탐색 시작 객체 (Map 또는 List)
     * @param pathTokens  parsePath 로 분리된 경로 토큰 ("[0]" 은 리스트 인덱스).
     *                    빈 리스트면 root 그대로 반환 (namespace 단독 — 기존엔 없던 케이스 방어).
     * @return 탐색 결과 값 (없으면 null)
     */
    @SuppressWarnings("unchecked")
    private Object traverse(Object root, List<String> pathTokens) {
        if (root == null) return null;
        if (pathTokens == null || pathTokens.isEmpty()) return root;

        Object current = root;
        for (String token : pathTokens) {
            if (current == null) return null;
            if (token.startsWith("[") && token.endsWith("]")) {
                // 리스트 인덱스 접근
                if (!(current instanceof List<?> list)) return null;
                int idx;
                try {
                    idx = Integer.parseInt(token.substring(1, token.length() - 1).trim());
                } catch (NumberFormatException e) {
                    return null;
                }
                if (idx < 0 || idx >= list.size()) return null;
                current = list.get(idx);
            } else {
                // 맵 키 접근
                if (!(current instanceof Map)) return null;
                current = ((Map<String, Object>) current).get(token);
            }
        }
        return current;
    }

    /**
     * "a.b[0].c" → ["a", "b", "[0]", "c"]. 단일 토큰("foo")은 ["foo"] 그대로.
     * 대괄호 인덱스는 독립 토큰으로 분리하여 traverse 가 List/Map 을 구분 처리하게 한다.
     */
    private List<String> parsePath(String path) {
        List<String> tokens = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '.') {
                if (buf.length() > 0) { tokens.add(buf.toString()); buf.setLength(0); }
            } else if (c == '[') {
                if (buf.length() > 0) { tokens.add(buf.toString()); buf.setLength(0); }
                buf.append(c);
            } else if (c == ']') {
                buf.append(c);
                tokens.add(buf.toString());
                buf.setLength(0);
            } else {
                buf.append(c);
            }
        }
        if (buf.length() > 0) tokens.add(buf.toString());
        return tokens;
    }

    /** mutable stepResults 복사본으로 새 컨텍스트 생성 (스텝 결과 추가 후 반환) */
    public StepContext withStepResult(String stepId, Map<String, Object> result) {
        Map<String, Object> newResults = new LinkedHashMap<>(stepResults != null ? stepResults : Map.of());
        newResults.put(stepId, result);
        return new StepContext(workflowRunId, workflowId, sessionId, inputData, newResults, loopData);
    }

    /**
     * CR-085 P2: 채널 reducer 적용 스텝 결과 병합.
     *
     * <p><b>하위호환 절대 규칙</b>: {@code outputChannel} 이 null/blank 이거나 {@code reduce} 가
     * null/"replace" 이면 {@link #withStepResult(String, Map)} 와 100% 동일 동작
     * (stepResults[stepId] = result 덮어쓰기). 즉 reducer 미지정 = 현행 동작 완전 보존.
     *
     * <p>채널 지정 시 {@code stepResults[outputChannel]} 에 누적:
     * <ul>
     *   <li>{@code append}: 채널을 List 로 보고 result 를 원소로 추가 (LangGraph add_messages 대응)</li>
     *   <li>{@code merge}: 채널을 Map 으로 보고 result 키들을 병합 (얕은 병합, 신규 키 우선)</li>
     *   <li>{@code replace}/그 외: 채널을 result 로 덮어쓰기</li>
     * </ul>
     * 채널에 누적하더라도 {@code stepResults[stepId]=result} 는 항상 함께 기록 →
     * 기존 {@code {{stepId.field}}} 참조는 reducer 사용 여부와 무관하게 그대로 동작.
     *
     * @param stepId        스텝 ID (항상 stepResults 에 result 로 기록)
     * @param result         이번 스텝 출력
     * @param outputChannel 누적 대상 채널명 (null/blank = 채널 미사용 = 기존 동작)
     * @param reduce         "replace"(기본) | "append" | "merge"
     */
    @SuppressWarnings("unchecked")
    public StepContext withStepResult(String stepId, Map<String, Object> result,
                                      String outputChannel, String reduce) {
        Map<String, Object> newResults = new LinkedHashMap<>(stepResults != null ? stepResults : Map.of());
        newResults.put(stepId, result);  // 기존 {{stepId.*}} 참조 보존 — 항상 기록

        if (outputChannel != null && !outputChannel.isBlank()
                && reduce != null && !"replace".equalsIgnoreCase(reduce)) {
            Object existing = newResults.get(outputChannel);
            if ("append".equalsIgnoreCase(reduce)) {
                List<Object> acc = (existing instanceof List)
                        ? new ArrayList<>((List<Object>) existing) : new ArrayList<>();
                acc.add(result);
                newResults.put(outputChannel, acc);
            } else if ("merge".equalsIgnoreCase(reduce)) {
                Map<String, Object> acc = (existing instanceof Map)
                        ? new LinkedHashMap<>((Map<String, Object>) existing) : new LinkedHashMap<>();
                acc.putAll(result);
                newResults.put(outputChannel, acc);
            } else {
                log.warn("Unknown reduce strategy '{}' for channel '{}' — falling back to replace",
                        reduce, outputChannel);
                newResults.put(outputChannel, result);
            }
        } else if (outputChannel != null && !outputChannel.isBlank()) {
            // 채널 지정 + reduce 미지정/replace → 채널을 덮어쓰기 (명시적 replace)
            newResults.put(outputChannel, result);
        }
        return new StepContext(workflowRunId, workflowId, sessionId, inputData, newResults, loopData);
    }

    /**
     * CR-055: EVALUATOR_LOOP iteration 진입 시 loopData를 주입한 새 컨텍스트 반환.
     * iteration/previous_output/feedback/generator_output 등 루프 내부 변수를 generator/evaluator 프롬프트에서
     * {{loop.iteration}}, {{loop.previous_output}} 형태로 참조 가능하게 한다.
     */
    public StepContext withLoopVars(Map<String, Object> loopVars) {
        return new StepContext(workflowRunId, workflowId, sessionId, inputData, stepResults, loopVars);
    }
}
