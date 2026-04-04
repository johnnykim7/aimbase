package com.platform.repository.master;

import com.platform.domain.master.AgentAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentAccountRepository extends JpaRepository<AgentAccountEntity, String> {

    List<AgentAccountEntity> findByAgentTypeAndStatusOrderByPriorityDesc(String agentType, String status);

    List<AgentAccountEntity> findByStatusOrderByPriorityDesc(String status);
}
