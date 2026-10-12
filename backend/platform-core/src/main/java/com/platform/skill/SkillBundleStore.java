package com.platform.skill;

import com.platform.domain.*;
import com.platform.repository.SkillBundleFileRepositoryBase;
import com.platform.repository.SkillBundleRepositoryBase;
import com.platform.repository.SkillBundleVersionRepositoryBase;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * CR-147: 한 범위(테넌트 전용 또는 플랫폼 공용)의 스킬 묶음 보관.
 * 두 범위는 DB 와 트랜잭션 관리자만 다르므로 판 매기기(BIZ-119)는 여기 한 곳에 둔다.
 * 쓰기 메서드의 트랜잭션은 하위 클래스가 자기 트랜잭션 관리자로 건다.
 */
public abstract class SkillBundleStore<B extends SkillBundleBase, V extends SkillBundleVersionBase, F extends SkillBundleFileBase> {

    protected final SkillBundleRepositoryBase<B> bundles;
    protected final SkillBundleVersionRepositoryBase<V> versions;
    protected final SkillBundleFileRepositoryBase<F> files;

    protected SkillBundleStore(SkillBundleRepositoryBase<B> bundles,
                               SkillBundleVersionRepositoryBase<V> versions,
                               SkillBundleFileRepositoryBase<F> files) {
        this.bundles = bundles;
        this.versions = versions;
        this.files = files;
    }

    public abstract SkillScope scope();

    protected abstract B newBundle();

    protected abstract V newVersion();

    protected abstract F newFile();

    public abstract UploadOutcome save(ParsedSkillBundle parsed, String uploadedBy);

    public abstract void setActive(String name, boolean active);

    public Optional<SkillBundleBase> findBundle(String name) {
        return bundles.findByName(name).map(SkillBundleBase.class::cast);
    }

    public List<SkillBundleBase> listBundles() {
        return new ArrayList<>(bundles.findAllByOrderByNameAsc());
    }

    public List<SkillBundleBase> listActiveBundles() {
        return new ArrayList<>(bundles.findByIsActiveTrueOrderByNameAsc());
    }

    public Optional<SkillBundleVersionBase> findVersion(UUID bundleId, int version) {
        return versions.findByBundleIdAndVersion(bundleId, version).map(SkillBundleVersionBase.class::cast);
    }

    public List<SkillBundleVersionBase> listVersions(UUID bundleId) {
        return new ArrayList<>(versions.findByBundleIdOrderByVersionDesc(bundleId));
    }

    public List<SkillBundleFileInfo> listFiles(UUID versionId) {
        return files.findInfoByVersionId(versionId);
    }

    public Optional<SkillBundleFileBase> findFile(UUID versionId, String path) {
        return files.findById(new SkillBundleFileId(versionId, path)).map(SkillBundleFileBase.class::cast);
    }

    public List<SkillBundleFileBase> loadFiles(UUID versionId) {
        return new ArrayList<>(files.findByIdVersionIdOrderByIdPathAsc(versionId));
    }

    /** 직전 판과 내용이 같으면 새 판을 만들지 않고, 비활성이었으면 활성으로 돌린다(BIZ-119). */
    protected UploadOutcome doSave(ParsedSkillBundle parsed, String uploadedBy) {
        Optional<B> existing = bundles.findForUpdateByName(parsed.name());
        B bundle;
        int nextVersion;
        boolean reactivated = false;
        if (existing.isPresent()) {
            bundle = existing.get();
            reactivated = !bundle.isActive();
            V latest = versions.findByBundleIdAndVersion(bundle.getId(), bundle.getLatestVersion())
                    .orElseThrow(() -> new IllegalStateException(
                            "skill bundle " + parsed.name() + " latest version " + bundle.getLatestVersion() + " missing"));
            if (latest.getContentSha256().equals(parsed.contentSha256())) {
                if (reactivated) {
                    bundle.setActive(true);
                    bundles.save(bundle);
                }
                return new UploadOutcome(bundle.getName(), scope(), latest.getVersion(), false, reactivated,
                        latest.getWarnings());
            }
            nextVersion = bundle.getLatestVersion() + 1;
        } else {
            bundle = newBundle();
            bundle.setId(UUID.randomUUID());
            bundle.setName(parsed.name());
            nextVersion = 1;
        }
        bundle.setDescription(parsed.description());
        bundle.setLatestVersion(nextVersion);
        bundle.setActive(true);
        bundles.save(bundle);

        V version = newVersion();
        version.setId(UUID.randomUUID());
        version.setBundleId(bundle.getId());
        version.setVersion(nextVersion);
        version.setDescription(parsed.description());
        version.setFrontmatter(parsed.frontmatter());
        version.setInstructions(parsed.instructions());
        version.setFileCount(parsed.files().size());
        version.setTotalBytes(parsed.totalBytes());
        version.setContentSha256(parsed.contentSha256());
        version.setWarnings(parsed.warnings());
        version.setUploadedBy(uploadedBy);
        versions.save(version);

        List<F> rows = new ArrayList<>(parsed.files().size());
        for (ParsedSkillBundle.File file : parsed.files()) {
            F row = newFile();
            row.setId(new SkillBundleFileId(version.getId(), file.path()));
            row.setSizeBytes(file.content().length);
            row.setSha256(file.sha256());
            row.setText(file.isText());
            row.setContent(file.content());
            rows.add(row);
        }
        files.saveAll(rows);
        return new UploadOutcome(bundle.getName(), scope(), nextVersion, true, reactivated, parsed.warnings());
    }

    protected void doSetActive(String name, boolean active) {
        B bundle = bundles.findForUpdateByName(name)
                .orElseThrow(() -> new SkillNotFoundException("스킬 묶음이 없습니다: " + name));
        if (bundle.isActive() != active) {
            bundle.setActive(active);
            bundles.save(bundle);
        }
    }
}
