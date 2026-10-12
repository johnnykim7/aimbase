package com.platform.skill;

import com.platform.domain.SkillBundleEntity;
import com.platform.domain.SkillBundleFileEntity;
import com.platform.domain.SkillBundleVersionEntity;
import com.platform.repository.SkillBundleFileRepository;
import com.platform.repository.SkillBundleRepository;
import com.platform.repository.SkillBundleVersionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** CR-147: 테넌트 전용 스킬 묶음 (Tenant DB). */
@Component
public class TenantSkillBundleStore
        extends SkillBundleStore<SkillBundleEntity, SkillBundleVersionEntity, SkillBundleFileEntity> {

    public TenantSkillBundleStore(SkillBundleRepository bundles,
                                  SkillBundleVersionRepository versions,
                                  SkillBundleFileRepository files) {
        super(bundles, versions, files);
    }

    @Override
    public SkillScope scope() { return SkillScope.TENANT; }

    @Override
    protected SkillBundleEntity newBundle() { return new SkillBundleEntity(); }

    @Override
    protected SkillBundleVersionEntity newVersion() { return new SkillBundleVersionEntity(); }

    @Override
    protected SkillBundleFileEntity newFile() { return new SkillBundleFileEntity(); }

    @Override
    @Transactional
    public UploadOutcome save(ParsedSkillBundle parsed, String uploadedBy) {
        return doSave(parsed, uploadedBy);
    }

    @Override
    @Transactional
    public void setActive(String name, boolean active) {
        doSetActive(name, active);
    }
}
