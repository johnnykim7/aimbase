package com.platform.repository;

import com.platform.domain.SkillBundleFileBase;
import com.platform.domain.SkillBundleFileId;
import com.platform.domain.SkillBundleFileInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * CR-147: 테넌트 전용과 플랫폼 공용 스킬 묶음 파일 리포지토리가 같이 쓰는 질의.
 */
@NoRepositoryBean
public interface SkillBundleFileRepositoryBase<T extends SkillBundleFileBase> extends JpaRepository<T, SkillBundleFileId> {

    /** 목록은 내용(BYTEA)을 읽지 않는다. */
    @Query("select new com.platform.domain.SkillBundleFileInfo(f.id.path, f.sizeBytes, f.sha256, f.isText) "
            + "from #{#entityName} f where f.id.versionId = :versionId order by f.id.path")
    List<SkillBundleFileInfo> findInfoByVersionId(@Param("versionId") UUID versionId);

    List<T> findByIdVersionIdOrderByIdPathAsc(UUID versionId);
}
