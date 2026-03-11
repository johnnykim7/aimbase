package com.platform.llm.model;

import java.util.List;

public record UnifiedMessage(Role role, List<ContentBlock> content) {

    public enum Role {
        SYSTEM, USER, ASSISTANT, TOOL_RESULT
    }

    public static UnifiedMessage ofText(Role role, String text) {
        return new UnifiedMessage(role, List.of(new ContentBlock.Text(text)));
    }

    public static UnifiedMessage ofAssistantWithToolUse(List<ContentBlock.ToolUse> toolUses) {
        return new UnifiedMessage(Role.ASSISTANT, List.copyOf(toolUses));
    }

    public static UnifiedMessage ofToolResults(List<ContentBlock.ToolResult> results) {
        return new UnifiedMessage(Role.TOOL_RESULT, List.copyOf(results));
    }
}
