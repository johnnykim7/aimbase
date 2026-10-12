package com.platform.skill;

import com.platform.domain.SkillBundleFileEntity;
import com.platform.domain.SkillBundleFileId;
import com.platform.domain.SkillBundleVersionEntity;
import com.platform.repository.PromptTemplateRepository;
import com.platform.service.PromptTemplateService;
import com.platform.workflow.StepContext;
import com.platform.workflow.model.WorkflowStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CR-147 단계 스킬 지정의 고정과 붙일 글. TC-SKB-022, 023 의 해석과 글 부분.
 * 안내 문구는 resources/prompts 의 실제 파일을 읽는다.
 */
class StepSkillServiceTest {

    private final UUID versionId = UUID.randomUUID();
    private final ResolvedSkill demo = new ResolvedSkill("demo", SkillScope.TENANT, 2,
            "Writes proposals.\nUse for bids.", versionId);
    private SkillCatalog catalog;
    private TenantSkillBundleStore store;
    private SkillBundleSettings settings;
    private StepSkillService service;

    @BeforeEach
    void setUp() {
        catalog = mock(SkillCatalog.class);
        store = mock(TenantSkillBundleStore.class);
        settings = mock(SkillBundleSettings.class);
        when(settings.inlineMaxBytes()).thenReturn(262144);
        when(catalog.store(SkillScope.TENANT)).thenAnswer(inv -> store);
        when(catalog.resolve("demo", null)).thenReturn(demo);
        SkillBundleVersionEntity version = new SkillBundleVersionEntity();
        version.setId(versionId);
        version.setVersion(2);
        version.setInstructions("# Demo\n\nWrite the proposal in five parts.\n");
        when(store.findVersionById(versionId)).thenReturn(Optional.of(version));
        PromptTemplateRepository repo = mock(PromptTemplateRepository.class);
        when(repo.findActiveByKey(any())).thenReturn(Optional.empty());
        service = new StepSkillService(catalog, settings, new PromptTemplateService(repo));
    }

    private void file(String path, String content, boolean text) {
        SkillBundleFileEntity f = new SkillBundleFileEntity();
        f.setId(new SkillBundleFileId(versionId, path));
        f.setContent(content.getBytes(StandardCharsets.UTF_8));
        f.setText(text);
        when(store.findFile(versionId, path)).thenAnswer(inv -> Optional.of(f));
    }

    private static WorkflowStep step(WorkflowStep.StepType type, Map<String, Object> config) {
        return new WorkflowStep("s1", "s1", type, config, List.of(), null, null, null);
    }

    @Test
    void TC_SKB_022_inline_text_wraps_instructions_and_files() {
        file("references/a.md", "Part one rules", true);
        StepSkillPins pins = service.pin(step(WorkflowStep.StepType.LLM_CALL,
                Map.of("skills", List.of(Map.of("name", "demo", "files", List.of("references/a.md"))))));

        String text = service.inlineText(pins.skills());

        assertThat(text).contains("<skill name=\"demo\" scope=\"tenant\" version=\"2\">",
                "Write the proposal in five parts.",
                "<file skill=\"demo\" path=\"references/a.md\">\nPart one rules\n</file>", "</skill>");
        assertThat(text).doesNotContain("{{");
        assertThat(pins.refs()).containsExactly(Map.of("name", "demo", "scope", "tenant", "version", 2));
    }

    @Test
    void TC_SKB_022_inline_text_over_limit_fails_with_size() {
        when(settings.inlineMaxBytes()).thenReturn(40);
        StepSkillPins pins = service.pin(step(WorkflowStep.StepType.LLM_CALL,
                Map.of("skills", List.of(Map.of("name", "demo")))));

        assertThatThrownBy(() -> service.inlineText(pins.skills()))
                .hasMessageContaining("BIZ-121")
                .hasMessageContaining("상한 40 bytes");
    }

    @Test
    void missing_or_binary_file_fails_the_step() {
        file("img/logo.png", "PNG", false);
        List<PinnedSkill> missing = List.of(new PinnedSkill(demo, List.of("references/none.md")));
        List<PinnedSkill> binary = List.of(new PinnedSkill(demo, List.of("img/logo.png")));
        when(store.findFile(versionId, "references/none.md")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.inlineText(missing)).hasMessageContaining("BIZ-120");
        assertThatThrownBy(() -> service.agentSystemMessage(binary)).hasMessageContaining("BIZ-121");
    }

    @Test
    void TC_SKB_023_agent_message_lists_skills_and_attaches_files() {
        file("references/a.md", "Part one rules", true);

        String message = service.agentSystemMessage(List.of(new PinnedSkill(demo, List.of("references/a.md"))));

        assertThat(message).contains("skill_open", "- demo: Writes proposals. Use for bids.",
                "<file skill=\"demo\" path=\"references/a.md\">");
        assertThat(message).doesNotContain("Write the proposal in five parts.");
    }

    @Test
    void TC_SKB_023_pinned_version_and_agents_are_resolved_separately() {
        ResolvedSkill v1 = new ResolvedSkill("demo", SkillScope.TENANT, 1, "d", UUID.randomUUID());
        ResolvedSkill other = new ResolvedSkill("other", SkillScope.PLATFORM, 4, "d", UUID.randomUUID());
        when(catalog.resolve("demo", 1)).thenReturn(v1);
        when(catalog.resolve("other", null)).thenReturn(other);

        StepSkillPins pins = service.pin(step(WorkflowStep.StepType.AGENT_CALL, Map.of(
                "skills", List.of(Map.of("name", "demo", "version", 1)),
                "agents", List.of(
                        Map.of("prompt", "a", "skills", List.of(Map.of("name", "other"), Map.of("name", "demo", "version", 1))),
                        Map.of("prompt", "b")))));

        assertThat(pins.skills()).extracting(PinnedSkill::skill).containsExactly(v1);
        assertThat(pins.forAgent(0)).extracting(PinnedSkill::skill).containsExactly(other, v1);
        assertThat(pins.forAgent(1)).isEmpty();
        assertThat(pins.refs()).hasSize(2);
        assertThat(StepSkillPins.toSkillSet(pins.skills()))
                .containsExactly(new com.platform.tool.SkillRef("demo", "tenant", 1));
    }

    @Test
    void other_step_types_and_duplicates() {
        StepSkillPins tool = service.pin(step(WorkflowStep.StepType.TOOL_CALL,
                Map.of("skills", List.of(Map.of("name", "demo")))));
        assertThat(tool.isEmpty()).isTrue();

        assertThatThrownBy(() -> service.pin(step(WorkflowStep.StepType.LLM_CALL,
                Map.of("skills", List.of(Map.of("name", "demo"), Map.of("name", "demo"))))))
                .hasMessageContaining("BIZ-121");
    }

    @Test
    void pins_from_engine_are_reused_only_for_the_same_step() {
        StepSkillPins engine = new StepSkillPins("s1", List.of(new PinnedSkill(demo, List.of())), List.of());
        StepContext ctx = new StepContext("r", "w", "sess", Map.of(), Map.of()).withSkillPins(engine);

        assertThat(service.pinsFor(step(WorkflowStep.StepType.LLM_CALL, Map.of()), ctx)).isSameAs(engine);
        verify(catalog, never()).resolve(any(), any());

        StepSkillPins other = service.pinsFor(new WorkflowStep("s2", "s2", WorkflowStep.StepType.LLM_CALL,
                Map.of(), List.of(), null, null, null), ctx);
        assertThat(other.stepId()).isEqualTo("s2");
        assertThat(other.isEmpty()).isTrue();
    }
}
