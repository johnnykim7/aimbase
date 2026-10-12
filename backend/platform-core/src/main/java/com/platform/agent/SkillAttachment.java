package com.platform.agent;

import com.platform.tool.SkillRef;

import java.util.List;

/**
 * CR-147: AGENT_CALL 단계가 시작할 때 고정한 스킬(T3-17 3.5, 3.6).
 *
 * @param skillSet      도구 호출이 받는 지정. ChatRequest 를 거쳐 ToolContext 까지 간다
 * @param systemMessage SubagentRunner 가 사용자 프롬프트 앞에 더할 스킬 목록과 files 내용
 */
public record SkillAttachment(List<SkillRef> skillSet, String systemMessage) {

    public SkillAttachment {
        skillSet = skillSet == null ? List.of() : List.copyOf(skillSet);
    }

    public boolean isEmpty() {
        return skillSet.isEmpty();
    }
}
