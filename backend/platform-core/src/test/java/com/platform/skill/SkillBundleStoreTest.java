package com.platform.skill;

import com.platform.domain.SkillBundleEntity;
import com.platform.domain.SkillBundleFileEntity;
import com.platform.domain.SkillBundleVersionEntity;
import com.platform.repository.SkillBundleFileRepository;
import com.platform.repository.SkillBundleRepository;
import com.platform.repository.SkillBundleVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * CR-147 BIZ-119: 판 매기기. TC-SKB-008 ~ TC-SKB-010.
 */
class SkillBundleStoreTest {

    private SkillBundleRepository bundles;
    private SkillBundleVersionRepository versions;
    private SkillBundleFileRepository files;
    private TenantSkillBundleStore store;

    @BeforeEach
    void setUp() {
        bundles = mock(SkillBundleRepository.class);
        versions = mock(SkillBundleVersionRepository.class);
        files = mock(SkillBundleFileRepository.class);
        store = new TenantSkillBundleStore(bundles, versions, files);
    }

    private static ParsedSkillBundle parsed(String description, String sha) {
        return new ParsedSkillBundle("demo", description, Map.of("name", "demo"), "본문",
                List.of(new ParsedSkillBundle.File("SKILL.md", new byte[]{1}, "f".repeat(64), true),
                        new ParsedSkillBundle.File("references/a.md", new byte[]{2, 3}, "e".repeat(64), true)),
                3, sha, List.of(), null);
    }

    private SkillBundleEntity existing(int latest, boolean active, String latestSha) {
        SkillBundleEntity b = new SkillBundleEntity();
        b.setId(UUID.randomUUID());
        b.setName("demo");
        b.setDescription("old");
        b.setLatestVersion(latest);
        b.setActive(active);
        SkillBundleVersionEntity v = new SkillBundleVersionEntity();
        v.setId(UUID.randomUUID());
        v.setBundleId(b.getId());
        v.setVersion(latest);
        v.setContentSha256(latestSha);
        v.setWarnings(List.of(Map.of("code", "MISSING_REFERENCE", "path", "references/x.md")));
        when(bundles.findForUpdateByName("demo")).thenReturn(Optional.of(b));
        when(versions.findByBundleIdAndVersion(b.getId(), latest)).thenReturn(Optional.of(v));
        return b;
    }

    @Test
    void first_upload_creates_version_1_with_files() {
        when(bundles.findForUpdateByName("demo")).thenReturn(Optional.empty());

        UploadOutcome outcome = store.save(parsed("d1", "a".repeat(64)), "admin");

        assertThat(outcome.version()).isEqualTo(1);
        assertThat(outcome.created()).isTrue();
        assertThat(outcome.scope()).isEqualTo(SkillScope.TENANT);
        ArgumentCaptor<SkillBundleVersionEntity> v = ArgumentCaptor.forClass(SkillBundleVersionEntity.class);
        verify(versions).save(v.capture());
        assertThat(v.getValue().getFileCount()).isEqualTo(2);
        assertThat(v.getValue().getUploadedBy()).isEqualTo("admin");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SkillBundleFileEntity>> f = ArgumentCaptor.forClass(List.class);
        verify(files).saveAll(f.capture());
        assertThat(f.getValue()).extracting(SkillBundleFileEntity::getPath)
                .containsExactly("SKILL.md", "references/a.md");
        assertThat(f.getValue()).allMatch(row -> row.getId().getVersionId().equals(v.getValue().getId()));
    }

    @Test
    void TC_SKB_008_changed_content_makes_next_version() {
        SkillBundleEntity b = existing(1, true, "a".repeat(64));

        UploadOutcome outcome = store.save(parsed("d2", "b".repeat(64)), "admin");

        assertThat(outcome.version()).isEqualTo(2);
        assertThat(outcome.created()).isTrue();
        assertThat(b.getLatestVersion()).isEqualTo(2);
        assertThat(b.getDescription()).isEqualTo("d2");
    }

    @Test
    void TC_SKB_009_same_content_keeps_version() {
        SkillBundleEntity b = existing(2, true, "a".repeat(64));

        UploadOutcome outcome = store.save(parsed("d2", "a".repeat(64)), "admin");

        assertThat(outcome.version()).isEqualTo(2);
        assertThat(outcome.created()).isFalse();
        assertThat(outcome.reactivated()).isFalse();
        assertThat(outcome.warnings()).hasSize(1);
        verify(versions, never()).save(any());
        verify(files, never()).saveAll(anyList());
        assertThat(b.getLatestVersion()).isEqualTo(2);
    }

    @Test
    void TC_SKB_010_reupload_reactivates() {
        SkillBundleEntity same = existing(1, false, "a".repeat(64));
        UploadOutcome sameOutcome = store.save(parsed("d", "a".repeat(64)), "admin");
        assertThat(sameOutcome.created()).isFalse();
        assertThat(sameOutcome.reactivated()).isTrue();
        assertThat(same.isActive()).isTrue();

        SkillBundleEntity changed = existing(1, false, "a".repeat(64));
        UploadOutcome changedOutcome = store.save(parsed("d", "c".repeat(64)), "admin");
        assertThat(changedOutcome.version()).isEqualTo(2);
        assertThat(changedOutcome.reactivated()).isTrue();
        assertThat(changed.isActive()).isTrue();
    }

    @Test
    void deactivate_keeps_versions() {
        SkillBundleEntity b = existing(3, true, "a".repeat(64));

        store.setActive("demo", false);

        assertThat(b.isActive()).isFalse();
        assertThat(b.getLatestVersion()).isEqualTo(3);
        verify(versions, never()).delete(any());
    }
}
