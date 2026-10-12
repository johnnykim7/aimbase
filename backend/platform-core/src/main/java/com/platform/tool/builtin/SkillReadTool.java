package com.platform.tool.builtin;

import com.platform.skill.SkillNotFoundException;
import com.platform.skill.SkillToolService;
import com.platform.tool.*;
import com.platform.tool.model.UnifiedToolDef;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * CR-147: 스킬 묶음 안의 파일 하나를 줄 번호와 함께 읽는다(T3-17 3.4).
 */
@Component
public class SkillReadTool implements EnhancedToolExecutor {

    public static final String NAME = "skill_read";

    private final SkillToolService skills;

    public SkillReadTool(SkillToolService skills) {
        this.skills = skills;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                NAME,
                "Reads one file inside a skill bundle, with line numbers. "
                        + "Long files are cut and the result tells the next offset to read from.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "name", Map.of("type", "string", "description", "Skill name"),
                                "path", Map.of("type", "string",
                                        "description", "Path inside the bundle, e.g. references/guide.md"),
                                "offset", Map.of("type", "integer",
                                        "description", "First line to read, starting at 1"),
                                "limit", Map.of("type", "integer", "description", "Number of lines to read")
                        ),
                        "required", List.of("name", "path")
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return ToolContractMeta.readOnlyNative(NAME, List.of("skill"));
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        Object name = input.get("name");
        Object path = input.get("path");
        if (!(name instanceof String n) || n.isBlank() || !(path instanceof String p) || p.isBlank()) {
            return ToolResult.error("INVALID_INPUT: name and path are required");
        }
        Integer offset;
        Integer limit;
        try {
            offset = toInteger(input.get("offset"));
            limit = toInteger(input.get("limit"));
        } catch (NumberFormatException e) {
            return ToolResult.error("INVALID_INPUT: offset and limit must be integers");
        }
        try {
            SkillToolService.Read read = skills.read(n.trim(), p.trim(), offset, limit,
                    ctx != null ? ctx.skillSet() : null);
            return ToolResult.ok(read.text(),
                    "Read " + read.path() + " from skill " + read.skill().name() + " v" + read.skill().version());
        } catch (SkillNotFoundException e) {
            return ToolResult.error(e.getMessage());
        }
    }

    /** CLI 통로는 정수를 문자열로 보내기도 한다. */
    private static Integer toInteger(Object value) {
        if (value == null) return null;
        if (value instanceof Number num) return num.intValue();
        String s = value.toString().trim();
        return s.isEmpty() ? null : Integer.valueOf(s);
    }
}
