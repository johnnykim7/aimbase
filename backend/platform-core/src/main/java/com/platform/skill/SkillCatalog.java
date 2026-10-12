package com.platform.skill;

import com.platform.domain.SkillBundleBase;
import com.platform.domain.SkillBundleVersionBase;
import com.platform.tool.SkillRef;
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

    /**
     * 스킬 도구 호출의 이름 해석. 지정이 없으면 활성 묶음을 이름으로 찾고(BIZ-120),
     * 지정이 있으면 지정한 이름만 받아 단계 시작 때 고정한 판을 쓴다(BIZ-121).
     */
    public ResolvedSkill resolveForCall(String name, List<SkillRef> skillSet) {
        if (skillSet == null || skillSet.isEmpty()) {
            return resolve(name, null);
        }
        SkillRef ref = skillSet.stream().filter(r -> r.name().equals(name)).findFirst()
                .orElseThrow(() -> new SkillNotFoundException("BIZ-121: 이 호출에 지정되지 않은 스킬입니다: " + name
                        + " (지정: " + skillSet.stream().map(SkillRef::name).toList() + ")"));
        return pinned(ref);
    }

    /** 고정한 범위와 판을 그대로 찾는다. 판은 바뀌지 않으므로 고정 뒤에 묶음이 비활성이 되어도 쓴다. */
    public ResolvedSkill pinned(SkillRef ref) {
        SkillScope scope = SkillScope.fromValue(ref.scope());
        if (scope == null) {
            throw new SkillNotFoundException("BIZ-120: 스킬 지정에 범위가 없습니다: " + ref.name());
        }
        SkillBundleStore<?, ?, ?> store = store(scope);
        SkillBundleBase bundle = store.findBundle(ref.name())
                .orElseThrow(() -> new SkillNotFoundException("BIZ-120: 스킬 묶음이 없습니다: " + ref.name()));
        SkillBundleVersionBase found = store.findVersion(bundle.getId(), ref.version())
                .orElseThrow(() -> new SkillNotFoundException(
                        "BIZ-120: 스킬 " + ref.name() + " 에 판 " + ref.version() + " 이 없습니다"));
        return new ResolvedSkill(ref.name(), scope, ref.version(), found.getDescription(), found.getId());
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
