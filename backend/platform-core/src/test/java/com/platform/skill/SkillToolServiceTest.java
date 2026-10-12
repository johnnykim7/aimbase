package com.platform.skill;

import com.platform.domain.SkillBundleFileEntity;
import com.platform.domain.SkillBundleFileId;
import com.platform.domain.SkillBundleFileInfo;
import com.platform.domain.SkillBundleVersionEntity;
import com.platform.service.PromptTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CR-147 skill_open, skill_read 결과 글. TC-SKB-013, TC-SKB-014.
 */
class SkillToolServiceTest {

    private final UUID versionId = UUID.randomUUID();
    private SkillCatalog catalog;
    private TenantSkillBundleStore store;
    private SkillBundleSettings settings;
    private SkillToolService service;

    @BeforeEach
    void setUp() {
        catalog = mock(SkillCatalog.class);
        store = mock(TenantSkillBundleStore.class);
        settings = mock(SkillBundleSettings.class);
        PromptTemplateService prompts = mock(PromptTemplateService.class);
        when(prompts.getTemplate(SkillToolService.OPEN_GUIDE_KEY)).thenReturn("Read files with skill_read.");
        when(settings.readMaxBytes()).thenReturn(SkillBundleSettings.DEFAULT_READ_MAX_BYTES);
        when(catalog.store(SkillScope.TENANT)).thenAnswer(inv -> store);
        when(catalog.resolveForCall(eq("demo"), any()))
                .thenReturn(new ResolvedSkill("demo", SkillScope.TENANT, 3, "desc", versionId));
        when(store.findFile(any(), anyString())).thenReturn(Optional.empty());
        service = new SkillToolService(catalog, settings, prompts);
    }

    private void version(String instructions) {
        SkillBundleVersionEntity v = new SkillBundleVersionEntity();
        v.setId(versionId);
        v.setVersion(3);
        v.setInstructions(instructions);
        when(store.findVersionById(versionId)).thenReturn(Optional.of(v));
        when(store.listFiles(versionId)).thenReturn(List.of(
                new SkillBundleFileInfo("SKILL.md", 120, "a".repeat(64), true),
                new SkillBundleFileInfo("assets/logo.png", 2048, "b".repeat(64), false),
                new SkillBundleFileInfo("references/guide.md", 300, "c".repeat(64), true)));
    }

    private void file(String path, String content, boolean text) {
        SkillBundleFileEntity f = new SkillBundleFileEntity();
        f.setId(new SkillBundleFileId(versionId, path));
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        f.setContent(bytes);
        f.setSizeBytes(bytes.length);
        f.setText(text);
        when(store.findFile(versionId, path)).thenReturn(Optional.of(f));
    }

    @Test
    void TC_SKB_013_open_returns_instructions_files_version_and_location() {
        version("# Demo\n\nStep 1. Read `references/guide.md`.\n");

        String text = service.open("demo", null, "/ws/run-1").text();

        assertThat(text).contains("Skill: demo (scope: tenant, version: 3)")
                .contains("Bundle directory: /ws/run-1/.skills/demo/v3/")
                .contains("Read files with skill_read.")
                .contains("# Demo\n\nStep 1. Read `references/guide.md`.")
                .contains("## Files (3)")
                .contains("- references/guide.md (300 bytes)")
                .contains("- assets/logo.png (2048 bytes, binary)")
                .doesNotContain("---");
    }

    @Test
    void open_without_workspace_has_no_location() {
        version("body");

        assertThat(service.open("demo", null, null).text()).doesNotContain("Bundle directory");
    }

    @Test
    void open_cuts_long_instructions_and_points_to_skill_read() {
        when(settings.readMaxBytes()).thenReturn(10);
        version("가나다라마바사아자차카타파하");

        String text = service.open("demo", null, null).text();

        assertThat(text).contains("가나다\n").contains("Instructions truncated at 10 bytes")
                .contains("skill_read path SKILL.md").doesNotContain("라");
    }

    @Test
    void TC_SKB_014_read_numbers_lines_with_offset_and_limit() {
        file("references/guide.md", "one\r\ntwo\nthree\nfour\n", true);

        String text = service.read("demo", "references/guide.md", 2, 2, null).text();

        assertThat(text).startsWith("2\ttwo\n3\tthree\n")
                .contains("Showing lines 2-3 of 4. Next offset: 4.")
                .doesNotContain("Truncated");
        assertThat(service.read("demo", "references/guide.md", null, null, null).text())
                .isEqualTo("1\tone\n2\ttwo\n3\tthree\n4\tfour\n");
    }

    @Test
    void TC_SKB_014_read_cuts_at_byte_limit_and_tells_next_offset() {
        when(settings.readMaxBytes()).thenReturn(21);
        file("references/guide.md", "aaaa\nbbbb\ncccc\ndddd\n", true);

        String text = service.read("demo", "references/guide.md", null, null, null).text();

        assertThat(text).startsWith("1\taaaa\n2\tbbbb\n3\tcccc\n")
                .contains("Truncated at 21 bytes. Showing lines 1-3 of 4. Next offset: 4.");
    }

    @Test
    void read_moves_past_a_line_longer_than_the_limit() {
        when(settings.readMaxBytes()).thenReturn(8);
        file("data.txt", "0123456789abcdef\nnext\n", true);

        String text = service.read("demo", "data.txt", 1, null, null).text();

        assertThat(text).startsWith("1\t012345\n").contains("Next offset: 2.");
        assertThat(service.read("demo", "data.txt", 2, null, null).text()).isEqualTo("2\tnext\n");
    }

    @Test
    void TC_SKB_014_binary_file_returns_size_only() {
        file("assets/logo.png", "PNGDATA", false);

        String text = service.read("demo", "assets/logo.png", null, null, null).text();

        assertThat(text).contains("not a text file (7 bytes)").doesNotContain("PNGDATA");
    }

    @Test
    void read_offset_past_end_and_missing_file() {
        file("a.md", "x\n", true);

        assertThat(service.read("demo", "a.md", 5, null, null).text()).contains("offset 5 is past the end");
        assertThatThrownBy(() -> service.read("demo", "nope.md", null, null, null))
                .isInstanceOf(SkillNotFoundException.class);
    }
}
