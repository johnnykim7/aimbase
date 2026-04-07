package com.platform.repository;

import com.platform.domain.PermissionRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * CR-030 PRD-197: PermissionRule 조회 레포지토리.
 */
@Repository
public interface PermissionRuleRepository extends JpaRepository<PermissionRuleEntity, String> {
    List<PermissionRuleEntity> findByIsActiveTrueOrderByPriorityDesc();
}
