package com.platform.repository;

import com.platform.domain.SkillEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * CR-035 PRD-237: 스킬 레포지토리 (Tenant DB).
 */
@Repository
public interface SkillRepository extends JpaRepository<SkillEntity, String> {

    List<SkillEntity> findByIsActiveTrueOrderByNameAsc();
}
