package com.platform.skill;

import com.platform.domain.SkillBundleBase;
import com.platform.domain.SkillBundleVersionBase;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * CR-147: 스킬 이름 해석 (BIZ-120). 활성 테넌트 전용이 활성 플랫폼 공용보다 먼저고,
 * 판 지정이 없으면 그 범위의 가장 큰 판이다.
 */
@Service
public class SkillCatalog {

    private final TenantSkillBundleStore tenantStore;
    private final PlatformSkillBundleStore platformStore;

    public SkillCatalog(TenantSkillBundleStore tenantStore, PlatformSkillBundleStore platformStore) {
        this.tenantStore = tenantStore;
        this.platformStore = platformStore;
    }

    public SkillBundleStore<?, ?, ?> store(SkillScope scope) {
        return scope == SkillScope.PLATFORM ? platformStore : tenantStore;
    }

    public ResolvedSkill resolve(String name, Integer version) {
        if (name == null || name.isBlank()) {
            throw new SkillNotFoundException("BIZ-120: 스킬 이름이 없습니다");
        }
        SkillBundleStore<?, ?, ?> store = tenantStore;
        Optional<SkillBundleBase> bundle = tenantStore.findBundle(name).filter(SkillBundleBase::isActive);
        if (bundle.isEmpty()) {
            store = platformStore;
            bundle = platformStore.findBundle(name).filter(SkillBundleBase::isActive);
        }
        if (bundle.isEmpty()) {
            throw new SkillNotFoundException("BIZ-120: 활성 스킬 묶음이 없습니다: " + name);
        }
        int v = version != null ? version : bundle.get().getLatestVersion();
        SkillBundleVersionBase found = store.findVersion(bundle.get().getId(), v)
                .orElseThrow(() -> new SkillNotFoundException("BIZ-120: 스킬 " + name + " 에 판 " + v + " 이 없습니다"));
        return new ResolvedSkill(name, store.scope(), v, found.getDescription(), found.getId());
    }

    /** 모델에게 보일 활성 스킬. 이름이 겹치면 테넌트 전용만 남긴다. */
    public List<ResolvedSkill> listActive() {
        Map<String, ResolvedSkill> byName = new TreeMap<>();
        for (SkillBundleStore<?, ?, ?> store : List.of(platformStore, tenantStore)) {
            for (SkillBundleBase b : store.listActiveBundles()) {
                store.findVersion(b.getId(), b.getLatestVersion()).ifPresent(v ->
                        byName.put(b.getName(), new ResolvedSkill(b.getName(), store.scope(),
                                v.getVersion(), v.getDescription(), v.getId())));
            }
        }
        return new ArrayList<>(byName.values());
    }
}
