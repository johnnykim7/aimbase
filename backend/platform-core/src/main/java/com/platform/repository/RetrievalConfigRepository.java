package com.platform.repository;

import com.platform.domain.RetrievalConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RetrievalConfigRepository extends JpaRepository<RetrievalConfigEntity, String> {
    List<RetrievalConfigEntity> findByIsActiveTrue();
}
