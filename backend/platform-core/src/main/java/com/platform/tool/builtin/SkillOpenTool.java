package com.platform.tool.builtin;

import com.platform.skill.SkillNotFoundException;
import com.platform.skill.SkillToolService;
import com.platform.tool.*;
import com.platform.tool.model.UnifiedToolDef;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * CR-147: 스킬 묶음의 안내서 본문과 파일 목록을 연다(T3-17 3.4). 스킬 지정이 있는 호출은 지정한 이름과 판만 받는다(BIZ-121).
 */
@Component
public class SkillOpenTool implements EnhancedToolExecutor {

    public static final String NAME = "skill_open";

    private final SkillToolService skills;

    public SkillOpenTool(SkillToolService skills) {
        this.skills = skills;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                NAME,
                "Opens a skill: returns its instructions (SKILL.md body), version and file list. "
                        + "Call this before using a skill listed in the conversation.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "name", Map.of("type", "string", "description", "Skill name")
                        ),
                        "required", List.of("name")
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
        if (!(name instanceof String s) || s.isBlank()) {
            return ToolResult.error("INVALID_INPUT: name is required");
        }
        try {
            SkillToolService.Opened opened = skills.open(s.trim(),
                    ctx != null ? ctx.skillSet() : null, ctx != null ? ctx.workspacePath() : null);
            return ToolResult.ok(opened.text(),
                    "Opened skill " + opened.skill().name() + " v" + opened.skill().version());
        } catch (SkillNotFoundException e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
