package com.platform.tool;

import com.platform.tool.builtin.CreateFileTool;
import com.platform.tool.builtin.DataQueryTool;

import java.util.Arrays;
import java.util.List;

/**
 * 실행기 통로 aimbase-data(/mcp-data)에 싣는 서버 도구(CR-183 BIZ-222, CR-182 3.2.2 BIZ-210).
 *
 * <p>CLI 연결의 모델은 서버 도구를 실행기가 붙인 MCP 통로로만 부른다. 내장 도구만 쓰는 방식(NATIVE)은
 * /mcp 통로를 붙이지 않으므로, 대화에만 주는 도구는 모든 방식에 붙는 이 통로 하나에 함께 싣는다.
 * 서버가 실행기에 열 도구를 목록으로 보내고, 실행기는 같은 목록을 머리글로 돌려줘 서버가 그 도구만 부른다.
 */
public final class DataChannelTools {

    /** 통로에 실을 수 있는 도구. 머리글과 허용 도구 이름의 순서도 이 순서다. */
    public static final List<String> NAMES = List.of(DataQueryTool.NAME, CreateFileTool.NAME);

    /** 목록을 싣지 않은 요청과 머리글. CR-183 만 반영된 서버나 실행기와 맞물리게 계산 도구 하나로 본다. */
    private static final List<String> DEFAULT = List.of(DataQueryTool.NAME);

    private DataChannelTools() {}

    /** 도구 거르기가 붙였고 빼지 않은 통로 도구. 없으면 빈 목록이다. */
    public static List<String> of(ToolFilterContext filter) {
        if (filter == null) return List.of();
        return NAMES.stream().filter(filter::attaches).toList();
    }

    /** 요청이 연 통로 도구. 비었으면 계산 도구 하나, 모르는 이름은 버린다. */
    public static List<String> open(List<String> requested) {
        if (requested == null || requested.isEmpty()) return DEFAULT;
        return NAMES.stream().filter(requested::contains).toList();
    }

    /** 머리글 값(쉼표 목록). */
    public static String encode(List<String> tools) {
        return String.join(",", tools);
    }

    /** 머리글 값을 연 도구 목록으로. 머리글이 없으면 계산 도구 하나다. */
    public static List<String> decode(String header) {
        if (header == null || header.isBlank()) return DEFAULT;
        return open(Arrays.stream(header.split(",")).map(String::trim).toList());
    }
}
