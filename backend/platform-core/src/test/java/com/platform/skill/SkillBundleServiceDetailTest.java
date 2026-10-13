package com.platform.skill;

import com.platform.domain.SkillBundleEntity;
import com.platform.domain.master.PlatformSkillBundleEntity;
import com.platform.policy.AuditLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CR-147 FE-038: 테넌트 화면 상세의 shadowed 가 목록과 같은 기준인지.
 * 상세 창은 이 값으로 "우리 전용 스킬이 있어 쓰이지 않습니다" 를 보여준다.
 */
class SkillBundleServiceDetailTest {

    private TenantSkillBundleStore tenant;
    private PlatformSkillBundleStore platform;
    private SkillBundleService service;

    @BeforeEach
    void setUp() {
        tenant = mock(TenantSkillBundleStore.class);
        platform = mock(PlatformSkillBundleStore.class);
        when(tenant.scope()).thenReturn(SkillScope.TENANT);
        when(platform.scope()).thenReturn(SkillScope.PLATFORM);
        when(tenant.findBundle(anyString())).thenReturn(Optional.empty());
        when(platform.findBundle(anyString())).thenReturn(Optional.empty());
        service = new SkillBundleService(mock(SkillBundleArchive.class), new SkillCatalog(tenant, platform),
                mock(SkillBundleSettings.class), mock(AuditLogger.class));

        PlatformSkillBundleEntity p = new PlatformSkillBundleEntity();
        p.setId(UUID.randomUUID());
        p.setName("pdf");
        p.setLatestVersion(1);
        p.setActive(true);
        when(platform.findBundle("pdf")).thenReturn(Optional.of(p));
    }

    private void tenantBundle(boolean active) {
        SkillBundleEntity t = new SkillBundleEntity();
        t.setId(UUID.randomUUID());
        t.setName("pdf");
        t.setLatestVersion(2);
        t.setActive(active);
        when(tenant.findBundle("pdf")).thenReturn(Optional.of(t));
    }

    @Test
    void platform_bundle_behind_active_tenant_bundle_is_shadowed() {
        tenantBundle(true);

        assertThat(service.getBundleForTenant("pdf", SkillScope.PLATFORM).get("shadowed")).isEqualTo(true);
        assertThat(service.getBundleForTenant("pdf", SkillScope.TENANT).get("shadowed")).isEqualTo(false);
    }

    @Test
    void inactive_tenant_bundle_does_not_shadow() {
        tenantBundle(false);

        assertThat(service.getBundleForTenant("pdf", SkillScope.PLATFORM).get("shadowed")).isEqualTo(false);
    }

    @Test
    void platform_screen_detail_is_never_shadowed() {
        tenantBundle(true);

        assertThat(service.getBundle("pdf", SkillScope.PLATFORM).get("shadowed")).isEqualTo(false);
    }
}
