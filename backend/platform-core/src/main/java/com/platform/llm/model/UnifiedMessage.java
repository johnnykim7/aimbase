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

    /**
     * CR-095: 멀티모달 USER 메시지 — 도구가 LLM 컨텍스트에 주입하는 document/image 블록.
     * openclaude 의 createUserMessage({content, isMeta:true}) 대응.
     */
    public static UnifiedMessage ofUserContent(List<ContentBlock> content) {
        return new UnifiedMessage(Role.USER, List.copyOf(content));
    }
}
