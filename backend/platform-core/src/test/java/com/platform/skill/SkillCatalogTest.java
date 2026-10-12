package com.platform.skill;

import com.platform.domain.SkillBundleBase;
import com.platform.domain.SkillBundleEntity;
import com.platform.domain.SkillBundleVersionEntity;
import com.platform.domain.master.PlatformSkillBundleEntity;
import com.platform.domain.master.PlatformSkillBundleVersionEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * CR-147 BIZ-120: 이름 해석. TC-SKB-011, TC-SKB-012, TC-SKB-026.
 */
class SkillCatalogTest {

    private TenantSkillBundleStore tenant;
    private PlatformSkillBundleStore platform;
    private SkillCatalog catalog;
    private final List<SkillBundleBase> tenantActive = new ArrayList<>();
    private final List<SkillBundleBase> platformActive = new ArrayList<>();

    @BeforeEach
    void setUp() {
        tenant = mock(TenantSkillBundleStore.class);
        platform = mock(PlatformSkillBundleStore.class);
        when(tenant.scope()).thenReturn(SkillScope.TENANT);
        when(platform.scope()).thenReturn(SkillScope.PLATFORM);
        when(tenant.findBundle(anyString())).thenReturn(Optional.empty());
        when(platform.findBundle(anyString())).thenReturn(Optional.empty());
        when(tenant.findVersion(org.mockito.ArgumentMatchers.any(), anyInt())).thenReturn(Optional.empty());
        when(platform.findVersion(org.mockito.ArgumentMatchers.any(), anyInt())).thenReturn(Optional.empty());
        when(tenant.listActiveBundles()).thenReturn(tenantActive);
        when(platform.listActiveBundles()).thenReturn(platformActive);
        catalog = new SkillCatalog(tenant, platform);
    }

    private void tenantBundle(String name, int latest, boolean active, int... versions) {
        SkillBundleEntity b = new SkillBundleEntity();
        b.setId(UUID.randomUUID());
        b.setName(name);
        b.setLatestVersion(latest);
        b.setActive(active);
        when(tenant.findBundle(name)).thenReturn(Optional.of(b));
        if (active) tenantActive.add(b);
        for (int v : versions) {
            SkillBundleVersionEntity ver = new SkillBundleVersionEntity();
            ver.setId(UUID.randomUUID());
            ver.setVersion(v);
            ver.setDescription("tenant v" + v);
            when(tenant.findVersion(b.getId(), v)).thenReturn(Optional.of(ver));
        }
    }

    private void platformBundle(String name, int latest, int... versions) {
        PlatformSkillBundleEntity b = new PlatformSkillBundleEntity();
        b.setId(UUID.randomUUID());
        b.setName(name);
        b.setLatestVersion(latest);
        b.setActive(true);
        when(platform.findBundle(name)).thenReturn(Optional.of(b));
        platformActive.add(b);
        for (int v : versions) {
            PlatformSkillBundleVersionEntity ver = new PlatformSkillBundleVersionEntity();
            ver.setId(UUID.randomUUID());
            ver.setVersion(v);
            ver.setDescription("platform v" + v);
            when(platform.findVersion(eq(b.getId()), eq(v))).thenReturn(Optional.of(ver));
        }
    }

    @Test
    void TC_SKB_011_tenant_wins_over_platform() {
        tenantBundle("demo", 1, true, 1);
        platformBundle("demo", 4, 4);

        ResolvedSkill r = catalog.resolve("demo", null);

        assertThat(r.scope()).isEqualTo(SkillScope.TENANT);
        assertThat(r.description()).isEqualTo("tenant v1");
        assertThat(catalog.listActive()).singleElement()
                .extracting(ResolvedSkill::scope).isEqualTo(SkillScope.TENANT);
    }

    @Test
    void inactive_tenant_falls_back_to_platform() {
        tenantBundle("demo", 1, false, 1);
        platformBundle("demo", 2, 1, 2);

        ResolvedSkill r = catalog.resolve("demo", null);

        assertThat(r.scope()).isEqualTo(SkillScope.PLATFORM);
        assertThat(r.version()).isEqualTo(2);
    }

    @Test
    void TC_SKB_012_version_pin() {
        tenantBundle("demo", 2, true, 1, 2);

        assertThat(catalog.resolve("demo", 1).description()).isEqualTo("tenant v1");
        assertThat(catalog.resolve("demo", null).version()).isEqualTo(2);
        assertThatThrownBy(() -> catalog.resolve("demo", 3))
                .isInstanceOf(SkillNotFoundException.class)
                .hasMessageContaining("BIZ-120");
    }

    @Test
    void TC_SKB_026_platform_only_resolves_as_platform() {
        platformBundle("shared", 1, 1);

        assertThat(catalog.resolve("shared", null).scope()).isEqualTo(SkillScope.PLATFORM);
        assertThatThrownBy(() -> catalog.resolve("none", null)).isInstanceOf(SkillNotFoundException.class);
    }
}
