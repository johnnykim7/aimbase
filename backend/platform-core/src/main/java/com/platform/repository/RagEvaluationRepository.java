package com.platform.repository;

import com.platform.domain.RagEvaluationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RagEvaluationRepository extends JpaRepository<RagEvaluationEntity, UUID> {

    List<RagEvaluationEntity> findBySourceIdOrderByCreatedAtDesc(String sourceId);
}
