package com.platform.repository;

import com.platform.domain.HookDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HookDefinitionRepository extends JpaRepository<HookDefinitionEntity, String> {
    List<HookDefinitionEntity> findByEventAndIsActiveTrueOrderByExecOrder(String event);
    List<HookDefinitionEntity> findByIsActiveTrueOrderByEventAscExecOrderAsc();
}
