package com.platform.repository;

import com.platform.domain.PolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PolicyRepository extends JpaRepository<PolicyEntity, String> {
    List<PolicyEntity> findByIsActiveTrueOrderByPriorityDesc();
    List<PolicyEntity> findByDomainAndIsActiveTrue(String domain);
}
