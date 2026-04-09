package com.platform.repository.master;

import com.platform.domain.master.PlatformWorkflowEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PlatformWorkflowRepository extends JpaRepository<PlatformWorkflowEntity, String> {

    List<PlatformWorkflowEntity> findByIsActiveTrueOrderByName();

    List<PlatformWorkflowEntity> findByCategoryAndIsActiveTrueOrderByName(String category);
}
