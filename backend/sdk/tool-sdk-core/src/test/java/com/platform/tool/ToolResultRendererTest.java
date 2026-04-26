package com.platform.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-067: ToolResultRenderer 단위 테스트.
 * EnhancedToolExecutor default bridge / AgentMcpServer 가 의존하는 핵심 직렬화 규칙을 잠근다.
 */
class ToolResultRendererTest {

    @Test
    @DisplayName("null ToolResult → 빈 문자열")
    void null_result_returns_empty_string() {
        assertThat(ToolResultRenderer.render(null)).isEmpty();
    }

    @Test
    @DisplayName("output 이 null 이고 success=true 면 summary 만 반환 (헤더 없음)")
    void null_output_success_returns_summary_only() {
        ToolResult result = ToolResult.ok(null, "작업 완료");
        assertThat(ToolResultRenderer.render(result)).isEqualTo("작업 완료");
    }

    @Test
    @DisplayName("output 이 CharSequence 면 헤더 + 본문 텍스트 반환")
    void string_output_with_header() {
        ToolResult result = ToolResult.ok("plain stdout text", "Command exit 0");
        String rendered = ToolResultRenderer.render(result);
        assertThat(rendered).isEqualTo("# Command exit 0\nplain stdout text");
    }

    @Test
    @DisplayName("Map output: content 키가 본문으로 추출되고 잔여 키는 메타로 부착됨 (FileReadTool 케이스)")
    void map_output_with_content_body_and_meta() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("path", "/abs/path/x.java");
        output.put("content", "1\tpackage com.x;\n2\tclass A {}\n");
        output.put("lineCount", 2);
        ToolResult result = ToolResult.ok(output, "x.java (2줄)");

        String rendered = ToolResultRenderer.render(result);
        assertThat(rendered)
                .startsWith("# x.java (2줄)\n1\tpackage com.x;\n2\tclass A {}\n")
                .contains("--- meta ---")
                .contains("\"path\"")
                .contains("/abs/path/x.java")
                .contains("\"lineCount\"")
                .doesNotContain("\"content\""); // content 는 본문으로 빠졌으니 메타에 중복되면 안 됨
    }

    @Test
    @DisplayName("Map output: stdout 키가 본문으로 추출됨 (BashTool 케이스)")
    void map_output_with_stdout_body() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("exit_code", 0);
        output.put("stdout", "Hello World\n");
        ToolResult result = ToolResult.ok(output, "Command completed (exit 0)");

        String rendered = ToolResultRenderer.render(result);
        assertThat(rendered).startsWith("# Command completed (exit 0)\nHello World\n")
                .contains("--- meta ---")
                .contains("\"exit_code\"");
    }

    @Test
    @DisplayName("Map output: 텍스트 본문 키 미발견 시 List 본문 키(matches) 추출 (GrepTool 케이스)")
    void map_output_falls_back_to_list_body_key() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("matches", List.of("a.java:1:foo", "b.java:5:foo"));
        output.put("matchCount", 2);
        ToolResult result = ToolResult.ok(output, "grep 'foo': 2 results");

        String rendered = ToolResultRenderer.render(result);
        assertThat(rendered).startsWith("# grep 'foo': 2 results\n[")
                .contains("a.java:1:foo")
                .contains("b.java:5:foo")
                .contains("--- meta ---")
                .contains("\"matchCount\"");
    }

    @Test
    @DisplayName("Map output: 본문 키 모두 미발견 시 전체 Map 을 JSON 으로 직렬화")
    void map_output_no_body_key_serializes_whole_map() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("file_path", "x.java");
        output.put("bytes_written", 42L);
        ToolResult result = ToolResult.ok(output, "Created: x.java");

        String rendered = ToolResultRenderer.render(result);
        assertThat(rendered).startsWith("# Created: x.java\n{")
                .contains("\"file_path\" : \"x.java\"")
                .contains("\"bytes_written\" : 42")
                .doesNotContain("--- meta ---"); // 별도 메타 섹션 없음
    }

    @Test
    @DisplayName("Collection output 은 헤더 + JSON 배열로 직렬화")
    void collection_output_serialized_as_json_array() {
        ToolResult result = ToolResult.ok(List.of("a", "b", "c"), "3 items");
        String rendered = ToolResultRenderer.render(result);
        assertThat(rendered).startsWith("# 3 items\n[")
                .contains("\"a\"").contains("\"b\"").contains("\"c\"");
    }

    @Test
    @DisplayName("success=false 일 때 [ERROR] prefix + summary + output 본문 부착")
    void error_result_prefixes_with_ERROR_and_keeps_body() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", 500);
        output.put("body", "Internal Server Error");
        ToolResult result = new ToolResult(false, output, "HTTP 500 Internal Server Error",
                List.of(), List.of(), Map.of(), null, 0L);

        String rendered = ToolResultRenderer.render(result);
        assertThat(rendered).startsWith("[ERROR] HTTP 500 Internal Server Error\n")
                .contains("Internal Server Error");
    }

    @Test
    @DisplayName("ToolResult.error() (output=null) 는 [ERROR] + summary 만")
    void error_result_with_null_output() {
        ToolResult result = ToolResult.error("File not found: /x");
        String rendered = ToolResultRenderer.render(result);
        assertThat(rendered).isEqualTo("[ERROR] File not found: /x");
    }

    @Test
    @DisplayName("summary 가 본문과 동일하면 헤더를 중복 부착하지 않음")
    void identical_summary_and_body_omits_header() {
        ToolResult result = ToolResult.ok("only body", "only body");
        assertThat(ToolResultRenderer.render(result)).isEqualTo("only body");
    }

    @Test
    @DisplayName("summary 가 비어있으면 헤더 없이 본문만")
    void empty_summary_omits_header() {
        Map<String, Object> output = Map.of("content", "raw text");
        ToolResult result = ToolResult.ok(output, "");
        String rendered = ToolResultRenderer.render(result);
        assertThat(rendered).isEqualTo("raw text");
    }
}
