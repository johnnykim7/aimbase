package com.platform.repository;

import com.platform.domain.ModelPricingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ModelPricingRepository extends JpaRepository<ModelPricingEntity, UUID> {
    Optional<ModelPricingEntity> findByModelName(String modelName);
    List<ModelPricingEntity> findByProvider(String provider);
    List<ModelPricingEntity> findByIsActiveTrue();
}
