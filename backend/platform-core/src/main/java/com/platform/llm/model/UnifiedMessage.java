package com.platform.llm.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/**
 * @param turnScoped CR-178: 이번 질문 몫의 시스템 지시(화면 맥락, 검색 자료, 기억, 프로젝트 지시문)라는 표시.
 *                   Claude CLI 작업자는 이어 쓰는 질문에서도, 시스템 지시 대체값이 있어도 이 메시지를 이번 질문 앞에
 *                   붙인다(BIZ-172). 대화 기록(Redis, DB)에 남으면 다음 질문에서 지난 화면 맥락까지 다시 붙으므로
 *                   직렬화하지 않는다. 맥락을 조립할 때마다 새로 단다.
 */
public record UnifiedMessage(Role role, List<ContentBlock> content, @JsonIgnore boolean turnScoped) {

    public enum Role {
        SYSTEM, USER, ASSISTANT, TOOL_RESULT
    }

    public UnifiedMessage(Role role, List<ContentBlock> content) {
        this(role, content, false);
    }

    public static UnifiedMessage ofText(Role role, String text) {
        return new UnifiedMessage(role, List.of(new ContentBlock.Text(text)));
    }

    /** CR-178: 이번 질문 몫의 시스템 지시. */
    public static UnifiedMessage turnScopedSystem(String text) {
        return new UnifiedMessage(Role.SYSTEM, List.of(new ContentBlock.Text(text)), true);
    }

    /** CR-178: 같은 내용에 이번 질문 몫 표시를 단 사본. 시스템 메시지가 아니면 그대로 돌려준다. */
    public UnifiedMessage asTurnScoped() {
        if (role != Role.SYSTEM || turnScoped) return this;
        return new UnifiedMessage(role, content, true);
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
