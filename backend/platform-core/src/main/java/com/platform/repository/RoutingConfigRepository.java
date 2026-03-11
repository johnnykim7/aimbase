package com.platform.repository;

import com.platform.domain.RoutingConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RoutingConfigRepository extends JpaRepository<RoutingConfigEntity, String> {
    List<RoutingConfigEntity> findByIsActiveTrue();
}
