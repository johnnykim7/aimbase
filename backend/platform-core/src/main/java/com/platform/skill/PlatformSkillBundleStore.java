package com.platform.skill;

import com.platform.domain.master.PlatformSkillBundleEntity;
import com.platform.domain.master.PlatformSkillBundleFileEntity;
import com.platform.domain.master.PlatformSkillBundleVersionEntity;
import com.platform.repository.master.PlatformSkillBundleFileRepository;
import com.platform.repository.master.PlatformSkillBundleRepository;
import com.platform.repository.master.PlatformSkillBundleVersionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** CR-147: 플랫폼 공용 스킬 묶음 (Master DB, BIZ-024). */
@Component
public class PlatformSkillBundleStore
        extends SkillBundleStore<PlatformSkillBundleEntity, PlatformSkillBundleVersionEntity, PlatformSkillBundleFileEntity> {

    public PlatformSkillBundleStore(PlatformSkillBundleRepository bundles,
                                    PlatformSkillBundleVersionRepository versions,
                                    PlatformSkillBundleFileRepository files) {
        super(bundles, versions, files);
    }

    @Override
    public SkillScope scope() { return SkillScope.PLATFORM; }

    @Override
    protected PlatformSkillBundleEntity newBundle() { return new PlatformSkillBundleEntity(); }

    @Override
    protected PlatformSkillBundleVersionEntity newVersion() { return new PlatformSkillBundleVersionEntity(); }

    @Override
    protected PlatformSkillBundleFileEntity newFile() { return new PlatformSkillBundleFileEntity(); }

    @Override
    @Transactional(transactionManager = "masterTransactionManager")
    public UploadOutcome save(ParsedSkillBundle parsed, String uploadedBy) {
        return doSave(parsed, uploadedBy);
    }

    @Override
    @Transactional(transactionManager = "masterTransactionManager")
    public void setActive(String name, boolean active) {
        doSetActive(name, active);
    }
}
