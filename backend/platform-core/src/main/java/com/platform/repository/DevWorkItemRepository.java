package com.platform.repository;

import com.platform.domain.DevWorkItemEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DevWorkItemRepository extends JpaRepository<DevWorkItemEntity, String> {
    Page<DevWorkItemEntity> findAllByOrderByStatusChangedAtDesc(Pageable pageable);
}
