package com.platform.skill;

import com.platform.domain.SkillBundleFileBase;
import com.platform.domain.SkillBundleVersionBase;
import com.platform.service.PromptTemplateService;
import com.platform.workflow.StepContext;
import com.platform.workflow.model.WorkflowStep;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CR-147: 워크플로우 단계의 스킬 지정(T3-17 3.5, 3.6, BIZ-121).
 * 단계가 시작할 때 이름과 판을 고정하고, LLM_CALL 에는 안내서와 파일을 붙일 글을,
 * AGENT_CALL 에는 스킬 목록 시스템 메시지를 만든다.
 */
@Service
public class StepSkillService {

    static final String LISTING_HEADER_KEY = "skill.listing.header";
    static final String INLINE_WRAPPER_KEY = "skill.inline.wrapper";

    private final SkillCatalog catalog;
    private final SkillBundleSettings settings;
    private final PromptTemplateService prompts;

    public StepSkillService(SkillCatalog catalog, SkillBundleSettings settings, PromptTemplateService prompts) {
        this.catalog = catalog;
        this.settings = settings;
        this.prompts = prompts;
    }

    /**
     * 단계 config 의 skills(멀티 에이전트면 agents[].skills 도)를 판까지 고정한다.
     * 판을 적지 않은 항목은 지금 가장 큰 판이다. 없는 이름이나 판은 단계 실패다(BIZ-120).
     */
    public StepSkillPins pin(WorkflowStep step) {
        boolean llm = step.type() == WorkflowStep.StepType.LLM_CALL;
        boolean agent = step.type() == WorkflowStep.StepType.AGENT_CALL;
        Map<String, Object> config = step.config();
        if ((!llm && !agent) || config == null) {
            return StepSkillPins.none(step.id());
        }
        List<PinnedSkill> skills = pinAll(config.get("skills"), "config.skills");
        List<List<PinnedSkill>> agentSkills = new ArrayList<>();
        if (agent && config.get("agents") instanceof List<?> agents) {
            for (int i = 0; i < agents.size(); i++) {
                agentSkills.add(agents.get(i) instanceof Map<?, ?> a
                        ? pinAll(a.get("skills"), "config.agents[" + i + "].skills")
                        : List.of());
            }
        }
        return new StepSkillPins(step.id(), skills, agentSkills);
    }

    /** 엔진이 단계 시작 때 고정해 둔 것을 쓴다. 엔진을 거치지 않은 실행(병렬 가지 등)이면 여기서 고정한다. */
    public StepSkillPins pinsFor(WorkflowStep step, StepContext context) {
        StepSkillPins pinned = context.skillPins();
        if (pinned != null && step.id().equals(pinned.stepId())) {
            return pinned;
        }
        return pin(step);
    }

    /**
     * LLM_CALL system 뒤에 붙일 글. 스킬마다 안내서 본문과 files 내용을 skill.inline.wrapper 로 감싼다.
     * 합계가 skills.inline-max-bytes 를 넘으면 단계 실패다.
     */
    public String inlineText(List<PinnedSkill> skills) {
        if (skills.isEmpty()) return "";
        String wrapper = template(INLINE_WRAPPER_KEY);
        StringBuilder out = new StringBuilder();
        for (PinnedSkill p : skills) {
            ResolvedSkill s = p.skill();
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("name", s.name());
            vars.put("scope", s.scope().value());
            vars.put("version", s.version());
            vars.put("instructions", version(s).getInstructions().strip());
            vars.put("files", files(p));
            if (!out.isEmpty()) out.append("\n\n");
            out.append(prompts.renderTemplate(wrapper, vars).strip());
        }
        checkSize(out, "LLM_CALL 에 붙일 스킬 안내서와 파일");
        return out.toString();
    }

    /**
     * AGENT_CALL 에 더할 시스템 메시지. skill.listing.header 아래 "- 이름: 설명" 목록,
     * 그 뒤에 files 로 지정한 파일 내용. 파일 내용 합계도 skills.inline-max-bytes 를 넘지 못한다.
     */
    public String agentSystemMessage(List<PinnedSkill> skills) {
        if (skills.isEmpty()) return "";
        StringBuilder out = new StringBuilder(template(LISTING_HEADER_KEY).strip()).append("\n\n");
        for (PinnedSkill p : skills) {
            String description = p.skill().description() == null ? "" : p.skill().description();
            out.append("- ").append(p.skill().name()).append(": ")
                    .append(description.replaceAll("\\s+", " ").strip()).append('\n');
        }
        StringBuilder attached = new StringBuilder();
        for (PinnedSkill p : skills) {
            attached.append(files(p));
        }
        checkSize(attached, "AGENT_CALL 에 붙일 스킬 파일");
        if (!attached.isEmpty()) {
            out.append('\n').append(attached);
        }
        return out.toString().strip();
    }

    private List<PinnedSkill> pinAll(Object value, String where) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> items)) {
            throw new IllegalArgumentException("BIZ-121: " + where + " 는 배열이어야 합니다");
        }
        List<PinnedSkill> pinned = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> m) || !(m.get("name") instanceof String name) || name.isBlank()) {
                throw new IllegalArgumentException("BIZ-121: " + where + " 항목에 name 이 없습니다");
            }
            if (!names.add(name.trim())) {
                throw new IllegalArgumentException("BIZ-121: " + where + " 에 같은 스킬이 두 번 있습니다: " + name.trim());
            }
            Integer version = m.get("version") instanceof Number n ? n.intValue() : null;
            List<String> files = new ArrayList<>();
            if (m.get("files") instanceof List<?> list) {
                list.forEach(f -> files.add(String.valueOf(f)));
            }
            pinned.add(new PinnedSkill(catalog.resolve(name.trim(), version), files));
        }
        return pinned;
    }

    private String files(PinnedSkill p) {
        ResolvedSkill s = p.skill();
        StringBuilder out = new StringBuilder();
        for (String path : p.files()) {
            SkillBundleFileBase file = catalog.store(s.scope()).findFile(s.versionId(), path)
                    .orElseThrow(() -> new SkillNotFoundException(
                            "BIZ-120: 스킬 " + s.name() + " 판 " + s.version() + " 에 파일이 없습니다: " + path));
            if (!file.isText()) {
                throw new IllegalArgumentException("BIZ-121: 텍스트 파일이 아니라 지시문에 붙일 수 없습니다: "
                        + s.name() + "/" + path);
            }
            out.append("<file skill=\"").append(s.name()).append("\" path=\"").append(path).append("\">\n")
                    .append(new String(file.getContent(), StandardCharsets.UTF_8).strip())
                    .append("\n</file>\n");
        }
        return out.toString();
    }

    private SkillBundleVersionBase version(ResolvedSkill s) {
        return catalog.store(s.scope()).findVersionById(s.versionId())
                .orElseThrow(() -> new SkillNotFoundException(
                        "BIZ-120: 스킬 " + s.name() + " 에 판 " + s.version() + " 이 없습니다"));
    }

    private void checkSize(CharSequence text, String what) {
        int bytes = text.toString().getBytes(StandardCharsets.UTF_8).length;
        int max = settings.inlineMaxBytes();
        if (bytes > max) {
            throw new IllegalStateException("BIZ-121: " + what + " 합계가 " + bytes + " bytes 로 상한 " + max
                    + " bytes 를 넘습니다");
        }
    }

    private String template(String key) {
        String text = prompts.getTemplate(key);
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("프롬프트 템플릿이 없습니다: " + key);
        }
        return text;
    }
}
