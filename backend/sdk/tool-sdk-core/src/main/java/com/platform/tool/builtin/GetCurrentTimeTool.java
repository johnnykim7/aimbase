package com.platform.tool.builtin;

import com.platform.tool.model.UnifiedToolDef;
import com.platform.tool.ToolExecutor;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 현재 시각을 ISO-8601 형식으로 반환하는 내장 도구.
 */
public class GetCurrentTimeTool implements ToolExecutor {

    private static final UnifiedToolDef DEFINITION = new UnifiedToolDef(
            "get_current_time",
            "현재 날짜와 시각을 ISO-8601 형식으로 반환합니다.",
            Map.of(
                    "type", "object",
                    "properties", Map.of(),
                    "required", java.util.List.of()
            )
    );

    @Override
    public UnifiedToolDef getDefinition() {
        return DEFINITION;
    }

    @Override
    public String execute(Map<String, Object> input) {
        return ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
