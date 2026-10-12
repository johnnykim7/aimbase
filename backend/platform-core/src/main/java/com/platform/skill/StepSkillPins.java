package com.platform.skill;

import com.platform.tool.SkillRef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CR-147: 한 단계가 시작할 때 고정한 스킬(BIZ-121). 재시도해도 같은 판을 쓰도록 단계 문맥에 실린다.
 * 멀티 에이전트 AGENT_CALL 은 에이전트 config 마다 따로 지정하므로 순서대로 따로 둔다.
 *
 * @param stepId 이 고정을 만든 단계. 다른 단계의 문맥에 남아 있어도 쓰지 않게 대조한다
 */
public record StepSkillPins(String stepId, List<PinnedSkill> skills, List<List<PinnedSkill>> agentSkills) {

    public StepSkillPins {
        skills = skills == null ? List.of() : List.copyOf(skills);
        agentSkills = agentSkills == null ? List.of() : agentSkills.stream().map(List::copyOf).toList();
    }

    public static StepSkillPins none(String stepId) {
        return new StepSkillPins(stepId, List.of(), List.of());
    }

    public boolean isEmpty() {
        return skills.isEmpty() && agentSkills.stream().allMatch(List::isEmpty);
    }

    public List<PinnedSkill> forAgent(int index) {
        return index >= 0 && index < agentSkills.size() ? agentSkills.get(index) : List.of();
    }

    /** FOREACH 처럼 한 번 고정한 것을 단계 이름이 다른 반복마다 쓸 때. */
    public StepSkillPins forStep(String otherStepId) {
        return new StepSkillPins(otherStepId, skills, agentSkills);
    }

    /** STEP_START payload 의 skills. 단계와 에이전트 지정을 합쳐 같은 판은 한 번만 싣는다. */
    public List<Map<String, Object>> refs() {
        Map<String, Map<String, Object>> distinct = new LinkedHashMap<>();
        List<PinnedSkill> all = new ArrayList<>(skills);
        agentSkills.forEach(all::addAll);
        for (PinnedSkill p : all) {
            ResolvedSkill s = p.skill();
            distinct.putIfAbsent(s.scope().value() + ":" + s.name() + ":" + s.version(), s.toRef());
        }
        return new ArrayList<>(distinct.values());
    }

    /** 도구 호출이 받는 지정(ToolContext.skillSet). */
    public static List<SkillRef> toSkillSet(List<PinnedSkill> pinned) {
        return pinned.stream()
                .map(p -> new SkillRef(p.skill().name(), p.skill().scope().value(), p.skill().version()))
                .toList();
    }
}
