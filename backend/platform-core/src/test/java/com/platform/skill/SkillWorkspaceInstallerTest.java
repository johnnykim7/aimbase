package com.platform.skill;

import com.platform.domain.SkillBundleFileEntity;
import com.platform.domain.SkillBundleFileId;
import com.platform.domain.SkillBundleVersionEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CR-147 묶음을 run 작업장에 푸는 일. TC-SKB-016 의 .skills/ 와 .complete 부분.
 */
class SkillWorkspaceInstallerTest {

    @TempDir
    Path workspace;

    private final UUID versionId = UUID.randomUUID();
    private final ResolvedSkill skill = new ResolvedSkill("demo", SkillScope.TENANT, 2, "desc", versionId);
    private TenantSkillBundleStore store;
    private SkillBundleVersionEntity version;
    private SkillWorkspaceInstaller installer;

    @BeforeEach
    void setUp() {
        SkillCatalog catalog = mock(SkillCatalog.class);
        store = mock(TenantSkillBundleStore.class);
        when(catalog.store(SkillScope.TENANT)).thenAnswer(inv -> store);
        version = new SkillBundleVersionEntity();
        version.setId(versionId);
        version.setVersion(2);
        version.setContentSha256("a".repeat(64));
        when(store.findVersionById(versionId)).thenReturn(Optional.of(version));
        installer = new SkillWorkspaceInstaller(catalog);
    }

    private static SkillBundleFileEntity file(UUID versionId, String path, String content) {
        SkillBundleFileEntity f = new SkillBundleFileEntity();
        f.setId(new SkillBundleFileId(versionId, path));
        f.setContent(content.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    @Test
    void TC_SKB_016_writes_files_and_marker_under_versioned_dir() throws Exception {
        when(store.loadFiles(versionId)).thenReturn(List.of(
                file(versionId, "SKILL.md", "# Demo"),
                file(versionId, "scripts/check.py", "print('ok')")));

        Path dir = installer.install(skill, workspace);

        assertThat(dir).isEqualTo(workspace.resolve(".skills/demo/v2"));
        assertThat(Files.readString(dir.resolve("scripts/check.py"))).isEqualTo("print('ok')");
        assertThat(Files.readString(dir.resolve(SkillWorkspaceInstaller.MARKER))).isEqualTo("tenant:" + "a".repeat(64));
    }

    @Test
    void same_stamp_is_not_rewritten() throws Exception {
        when(store.loadFiles(versionId)).thenReturn(List.of(file(versionId, "SKILL.md", "# Demo")));
        Path dir = installer.install(skill, workspace);
        Files.writeString(dir.resolve("out.txt"), "made by a script");

        installer.install(skill, workspace);

        verify(store, times(1)).loadFiles(versionId);
        assertThat(dir.resolve("out.txt")).exists();
    }

    @Test
    void different_stamp_replaces_the_whole_dir() throws Exception {
        when(store.loadFiles(versionId)).thenReturn(List.of(file(versionId, "old.md", "old")));
        Path dir = installer.install(skill, workspace);

        version.setContentSha256("b".repeat(64));
        when(store.loadFiles(versionId)).thenReturn(List.of(file(versionId, "new.md", "new")));
        installer.install(skill, workspace);

        assertThat(dir.resolve("old.md")).doesNotExist();
        assertThat(dir.resolve("new.md")).exists();
        assertThat(Files.readString(dir.resolve(SkillWorkspaceInstaller.MARKER))).endsWith("b".repeat(64));
    }

    @Test
    void file_path_outside_bundle_is_rejected() {
        when(store.loadFiles(versionId)).thenReturn(List.of(file(versionId, "../../escape.sh", "x")));

        assertThatThrownBy(() -> installer.install(skill, workspace))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BIZ-118");
        assertThat(workspace.resolve("escape.sh")).doesNotExist();
    }

    @Test
    void missing_version_is_reported() {
        when(store.findVersionById(versionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> installer.install(skill, workspace))
                .isInstanceOf(SkillNotFoundException.class)
                .hasMessageContaining("BIZ-120");
    }
}
