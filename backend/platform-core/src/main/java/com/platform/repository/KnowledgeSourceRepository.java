package com.platform.repository;

import com.platform.domain.KnowledgeSourceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KnowledgeSourceRepository extends JpaRepository<KnowledgeSourceEntity, String> {
    List<KnowledgeSourceEntity> findByType(String type);
    List<KnowledgeSourceEntity> findByStatus(String status);
}
