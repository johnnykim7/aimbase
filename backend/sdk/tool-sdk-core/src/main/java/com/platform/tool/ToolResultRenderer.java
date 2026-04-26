package com.platform.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CR-067: ToolResult → 문자열 본문 직렬화 표준 헬퍼.
 *
 * <p>EnhancedToolExecutor.execute(Map) default bridge 와 AgentMcpServer 등
 * "ToolResult 를 String 으로 어댑트해야 하는 모든 호출처" 가 공유한다.
 *
 * <p>핵심 규칙: ToolResult.summary() 만 반환하면 output 본문(파일 내용·stdout·grep 결과 등)이
 * 통째로 손실된다 (CR-050 Phase 9 후속 발견). 본 헬퍼는 output 의 본문 키를 휴리스틱으로 추출해
 * 텍스트 본문을 손실 없이 노출하면서, 메타 정보는 잔여 JSON 으로 동행시킨다.
 *
 * <p>본문 키 우선순위는 sdk-core builtin 도구 11개 + platform-core 빌트인 도구의 실제 output Map
 * 스키마 분석을 기반으로 한다 (FileReadTool=content, BashTool=stdout, GrepTool=matches,
 * GlobTool=filenames, SafeEditTool=diff, PatchApplyTool=diff/appliedDiff, StructuredSearchTool=results,
 * DocumentSectionReadTool=content/sections, WorkspaceSnapshotTool=tree, HttpRequestTool=body,
 * WebSearchTool=results).
 */
public final class ToolResultRenderer {

    private static final Logger log = LoggerFactory.getLogger(ToolResultRenderer.class);

    /**
     * Map 형태 output 에서 본문 텍스트가 들어가는 키. 발견된 첫 키의 값을 본문으로 사용한다.
     * 우선순위: 평문 텍스트가 큰 순서. List 형태 본문 키는 별도 폴백 처리.
     */
    private static final List<String> TEXT_BODY_KEYS = List.of(
            "content", "stdout", "text", "body", "tree", "diff", "appliedDiff");

    /**
     * Map 형태 output 에서 List/Collection 본문이 들어가는 키. 텍스트 본문 키 미발견 시 탐색.
     */
    private static final List<String> LIST_BODY_KEYS = List.of(
            "matches", "filenames", "results", "sections");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private ToolResultRenderer() {}

    /**
     * ToolResult 를 String 으로 직렬화한다.
     *
     * @param result ToolResult (null 허용 — 빈 문자열 반환)
     * @return 직렬화된 본문. summary 헤더 + output 본문 + 잔여 메타.
     */
    public static String render(ToolResult result) {
        if (result == null) {
            return "";
        }

        String summary = result.summary() == null ? "" : result.summary();
        Object output = result.output();

        if (!result.success()) {
            return renderError(summary, output);
        }

        if (output == null) {
            return summary;
        }

        if (output instanceof CharSequence cs) {
            return withHeader(summary, cs.toString());
        }

        if (output instanceof Map<?, ?> map) {
            return renderMap(summary, map);
        }

        // Collection / Number / Boolean / record / 기타 객체 → 전체 JSON
        return withHeader(summary, toJson(output));
    }

    /**
     * 에러 결과 직렬화: `[ERROR] {summary}\n{output?}`.
     * output 이 있으면 본문도 부착해 진단 정보를 잃지 않는다.
     */
    private static String renderError(String summary, Object output) {
        StringBuilder sb = new StringBuilder("[ERROR]");
        if (!summary.isEmpty()) {
            sb.append(' ').append(summary);
        }
        if (output != null) {
            String body = output instanceof CharSequence cs
                    ? cs.toString()
                    : output instanceof Map<?, ?> m ? renderMapBody(m) : toJson(output);
            if (!body.isEmpty()) {
                sb.append('\n').append(body);
            }
        }
        return sb.toString();
    }

    /**
     * Map output 직렬화: 본문 키 추출 + 잔여 키 메타 섹션.
     */
    private static String renderMap(String summary, Map<?, ?> map) {
        return withHeader(summary, renderMapBody(map));
    }

    private static String renderMapBody(Map<?, ?> map) {
        // 1. 텍스트 본문 키 우선
        for (String key : TEXT_BODY_KEYS) {
            Object value = map.get(key);
            if (value instanceof CharSequence cs && cs.length() > 0) {
                return appendMeta(cs.toString(), map, key);
            }
        }
        // 2. List/Collection 본문 키
        for (String key : LIST_BODY_KEYS) {
            Object value = map.get(key);
            if (value instanceof Collection<?> col && !col.isEmpty()) {
                return appendMeta(toJson(col), map, key);
            }
        }
        // 3. 본문 키 미발견 → 전체 JSON
        return toJson(map);
    }

    /**
     * 본문 뒤에 잔여 키(메타) JSON 을 부록으로 부착한다.
     * 메타가 없으면 본문만 반환.
     */
    private static String appendMeta(String body, Map<?, ?> map, String bodyKey) {
        Map<String, Object> meta = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String k = String.valueOf(entry.getKey());
            if (!k.equals(bodyKey)) {
                meta.put(k, entry.getValue());
            }
        }
        if (meta.isEmpty()) {
            return body;
        }
        return body + "\n\n--- meta ---\n" + toJson(meta);
    }

    /**
     * summary 가 비어있지 않으면 본문 앞에 `# {summary}\n` 헤더를 부착한다.
     * summary 와 body 가 정확히 일치하면 헤더를 생략 (중복 회피).
     */
    private static String withHeader(String summary, String body) {
        if (summary.isEmpty()) {
            return body;
        }
        if (body.equals(summary)) {
            return summary;
        }
        return "# " + summary + "\n" + body;
    }

    /**
     * Jackson pretty JSON 직렬화. 실패 시 toString() 폴백 + WARN 로그.
     */
    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("ToolResult output JSON 직렬화 실패, toString 폴백: type={}, error={}",
                    value == null ? "null" : value.getClass().getName(), e.getMessage());
            return String.valueOf(value);
        }
    }
}
