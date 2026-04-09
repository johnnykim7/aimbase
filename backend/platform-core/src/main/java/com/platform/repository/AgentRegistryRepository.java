package com.platform.repository;

import com.platform.domain.AgentRegistryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * CR-041: 원격 에이전트 레지스트리 리포지토리.
 */
public interface AgentRegistryRepository extends JpaRepository<AgentRegistryEntity, UUID> {

    List<AgentRegistryEntity> findByStatus(String status);

    List<AgentRegistryEntity> findByStatusAndLastHeartbeatAtBefore(String status, OffsetDateTime before);

    Optional<AgentRegistryEntity> findByPublicAddressAndMcpPortAndStatus(
            String publicAddress, int mcpPort, String status);

    List<AgentRegistryEntity> findByUserId(String userId);
}
