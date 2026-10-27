package com.platform.repository;

import com.platform.domain.DevWorkItemChangeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DevWorkItemChangeRepository extends JpaRepository<DevWorkItemChangeEntity, UUID> {
    List<DevWorkItemChangeEntity> findTop100ByWorkItemIdOrderByChangedAtDesc(String workItemId);
}
