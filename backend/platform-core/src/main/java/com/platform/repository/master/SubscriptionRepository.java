package com.platform.repository.master;

import com.platform.domain.master.SubscriptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SubscriptionRepository extends JpaRepository<SubscriptionEntity, String> {
    Optional<SubscriptionEntity> findByTenantId(String tenantId);
    List<SubscriptionEntity> findByPlan(String plan);
}
