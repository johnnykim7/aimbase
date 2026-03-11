package com.platform.repository;

import com.platform.domain.MCPServerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MCPServerRepository extends JpaRepository<MCPServerEntity, String> {
    List<MCPServerEntity> findByAutoStartTrue();
    List<MCPServerEntity> findByStatus(String status);
}
