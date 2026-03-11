package com.platform.repository;

import com.platform.domain.ConnectionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ConnectionRepository extends JpaRepository<ConnectionEntity, String> {
    List<ConnectionEntity> findByType(String type);
    List<ConnectionEntity> findByAdapter(String adapter);
    List<ConnectionEntity> findByStatus(String status);
}
