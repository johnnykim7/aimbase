package com.platform.skill;

import com.platform.config.PlatformSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.platform.skill.SkillBundleTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * CR-147 BIZ-118: 스킬 묶음 올리기 검사. TC-SKB-001 ~ TC-SKB-007.
 */
class SkillBundleArchiveTest {

    private PlatformSettingsService settings;
    private SkillBundleArchive archive;

    @BeforeEach
    void setUp() {
        settings = defaultSettings();
        archive = new SkillBundleArchive(new SkillBundleSettings(settings));
    }

    private static final String BODY = """
            # 데모

            단계마다 `references/01-a.md` 를 읽는다. 회의 기획은 `references/12-missing.md`.
            작업 상태는 `bid-project/_status.md` 에 남긴다. 참고 `/mnt/skills/public/docx/SKILL.md`.
            스크립트 `scripts/check.py`, 양식 [템플릿](assets/templates/form.md), 폴더 `assets/templates`.
            """;

    @Test
    void TC_SKB_001_top_folder_is_stripped_and_missing_reference_warned() {
        byte[] zip = zip(entries(
                "demo-skill/SKILL.md", skillMd("demo-skill", "데모 스킬", BODY),
                "demo-skill/references/01-a.md", "a",
                "demo-skill/scripts/check.py", "print(1)",
                "demo-skill/assets/templates/form.md", "form"));

        ParsedSkillBundle parsed = archive.parse(zip);

        assertThat(parsed.name()).isEqualTo("demo-skill");
        assertThat(parsed.topFolder()).isEqualTo("demo-skill");
        assertThat(parsed.files()).extracting(ParsedSkillBundle.File::path)
                .containsExactly("SKILL.md", "assets/templates/form.md", "references/01-a.md", "scripts/check.py");
        assertThat(parsed.instructions()).startsWith("# 데모").doesNotContain("description:");
        assertThat(parsed.warnings()).containsExactly(
                Map.of("code", "MISSING_REFERENCE", "path", "references/12-missing.md"));
    }

    @Test
    void TC_SKB_002_root_skill_md_keeps_paths() {
        ParsedSkillBundle parsed = archive.parse(zip(entries(
                "SKILL.md", skillMd("root-skill", "d", "본문"),
                "references/x.md", "x")));

        assertThat(parsed.topFolder()).isNull();
        assertThat(parsed.files()).extracting(ParsedSkillBundle.File::path)
                .containsExactly("SKILL.md", "references/x.md");
        assertThat(parsed.warnings()).isEmpty();
    }

    @Test
    void TC_SKB_003_skill_md_position() {
        assertRejected(entries("a/b/SKILL.md", skillMd("x", "d", "")), "SKILL.md 가 맨 위나");
        assertRejected(entries("README.md", "x"), "SKILL.md 가 맨 위나");
        assertRejected(entries("a/SKILL.md", skillMd("x", "d", ""), "b/c.md", "c"), "SKILL.md 가 맨 위나");
    }

    @Test
    void TC_SKB_004_frontmatter() {
        assertRejected(entries("SKILL.md", skillMd("Upper", "d", "")), "name");
        assertRejected(entries("SKILL.md", skillMd("a".repeat(65), "d", "")), "name");
        assertRejected(entries("SKILL.md", "---\nname: x\n---\n본문"), "description");
        assertRejected(entries("SKILL.md", skillMd("x", "가".repeat(1025), "")), "description");
        assertRejected(entries("SKILL.md", "본문만"), "앞머리");
        assertThat(archive.parse(zip(entries("SKILL.md", skillMd("x", "가".repeat(1024), "")))).name())
                .isEqualTo("x");
    }

    @Test
    void TC_SKB_005_path_escape_and_symlink() {
        assertRejected(entries("SKILL.md", skillMd("x", "d", ""), "../x", "x"), "묶음 밖");
        assertRejected(entries("SKILL.md", skillMd("x", "d", ""), "/etc/x", "x"), "절대 경로");
        assertThatThrownBy(() -> SkillBundleArchive.normalizePath("a\\b")).hasMessageContaining("쓸 수 없는 글자");
        assertThatThrownBy(() -> archive.parse(zip(entries("SKILL.md", skillMd("x", "d", "")), "link", "/etc/passwd")))
                .isInstanceOf(SkillBundleRejectedException.class)
                .hasMessageContaining("심볼릭 링크");
    }

    @Test
    void TC_SKB_006_size_and_count_limits() {
        Map<String, String> ok = entries("SKILL.md", skillMd("x", "d", ""), "a.md", "12345", "b.md", "12345");

        when(settings.getInt(eq("skills.bundle-max-files"), anyInt())).thenReturn(2);
        assertRejected(ok, "파일 수");
        when(settings.getInt(eq("skills.bundle-max-files"), anyInt())).thenReturn(500);

        when(settings.getLong(eq("skills.file-max-bytes"), anyLong())).thenReturn(4L);
        assertRejected(ok, "파일 하나");
        when(settings.getLong(eq("skills.file-max-bytes"), anyLong())).thenReturn(10_485_760L);

        byte[] zip = zip(ok);
        when(settings.getLong(eq("skills.bundle-max-bytes"), anyLong())).thenReturn((long) zip.length - 1);
        assertThatThrownBy(() -> archive.parse(zip)).hasMessageContaining("zip 크기");

        String big = "a".repeat(5000);
        byte[] compressible = zip(entries("SKILL.md", skillMd("x", "d", ""), "a.md", big, "b.md", big));
        when(settings.getLong(eq("skills.bundle-max-bytes"), anyLong())).thenReturn((long) compressible.length + 100);
        assertThatThrownBy(() -> archive.parse(compressible)).hasMessageContaining("푼 합계");
    }

    @Test
    void TC_SKB_007_ignored_entries() {
        ParsedSkillBundle parsed = archive.parse(zip(entries(
                "s/SKILL.md", skillMd("s", "d", ""),
                "s/.DS_Store", "x",
                "s/references/.DS_Store", "x",
                "__MACOSX/s/._SKILL.md", "x")));

        assertThat(parsed.files()).extracting(ParsedSkillBundle.File::path).containsExactly("SKILL.md");
        assertThat(parsed.topFolder()).isEqualTo("s");
    }

    @Test
    void content_hash_ignores_entry_order_and_follows_content() {
        String md = skillMd("x", "d", "");
        String a = archive.parse(zip(entries("SKILL.md", md, "a.md", "1", "b.md", "2"))).contentSha256();
        String b = archive.parse(zip(entries("b.md", "2", "SKILL.md", md, "a.md", "1"))).contentSha256();
        String c = archive.parse(zip(entries("SKILL.md", md, "a.md", "1", "b.md", "3"))).contentSha256();

        assertThat(a).isEqualTo(b).isNotEqualTo(c).hasSize(64);
    }

    @Test
    void folder_name_and_unsupported_script_warnings() {
        ParsedSkillBundle parsed = archive.parse(zip(entries(
                "folder/SKILL.md", skillMd("other", "d", ""),
                "folder/scripts/run.rb", "puts 1",
                "folder/scripts/ok.sh", "echo 1")));

        assertThat(parsed.warnings()).containsExactlyInAnyOrder(
                Map.of("code", "FOLDER_NAME_MISMATCH", "folder", "folder", "name", "other"),
                Map.of("code", "UNSUPPORTED_SCRIPT", "path", "scripts/run.rb"));
    }

    @Test
    void binary_file_is_not_text_and_frontmatter_kept() {
        ParsedSkillBundle parsed = archive.parse(zip(entries(
                "SKILL.md", "---\nname: x\ndescription: d\nallowed-tools: [Read, Bash]\n---\n본문",
                "assets/bin.dat", "a\u0000b")));

        assertThat(parsed.files()).filteredOn(f -> f.path().equals("assets/bin.dat"))
                .extracting(ParsedSkillBundle.File::isText).containsExactly(false);
        assertThat(parsed.frontmatter()).containsEntry("allowed-tools", List.of("Read", "Bash"));
    }

    private void assertRejected(Map<String, String> entries, String messagePart) {
        assertThatThrownBy(() -> archive.parse(zip(entries)))
                .isInstanceOf(SkillBundleRejectedException.class)
                .hasMessageStartingWith("BIZ-118")
                .hasMessageContaining(messagePart);
    }
}
