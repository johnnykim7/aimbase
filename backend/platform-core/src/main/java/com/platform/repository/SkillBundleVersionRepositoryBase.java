package com.platform.repository;

import com.platform.domain.SkillBundleVersionBase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * CR-147: 테넌트 전용과 플랫폼 공용 스킬 묶음 판 리포지토리가 같이 쓰는 질의.
 */
@NoRepositoryBean
public interface SkillBundleVersionRepositoryBase<T extends SkillBundleVersionBase> extends JpaRepository<T, UUID> {

    Optional<T> findByBundleIdAndVersion(UUID bundleId, int version);

    List<T> findByBundleIdOrderByVersionDesc(UUID bundleId);
}
