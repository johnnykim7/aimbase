package com.platform.repository.master;

import com.platform.domain.master.AgentAccountAssignmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AgentAccountAssignmentRepository extends JpaRepository<AgentAccountAssignmentEntity, Long> {

    /** 특정 테넌트+앱에 대한 활성 할당 (priority 내림차순) */
    @Query("SELECT a FROM AgentAccountAssignmentEntity a JOIN FETCH a.account " +
            "WHERE a.isActive = true AND a.account.status = 'active' " +
            "AND a.account.agentType = :agentType " +
            "AND (a.tenantId = :tenantId OR a.tenantId IS NULL) " +
            "AND (a.appId = :appId OR a.appId IS NULL) " +
            "ORDER BY " +
            "CASE WHEN a.tenantId IS NOT NULL AND a.appId IS NOT NULL THEN 0 " +
            "     WHEN a.tenantId IS NOT NULL THEN 1 " +
            "     WHEN a.appId IS NOT NULL THEN 2 " +
            "     ELSE 3 END, " +
            "a.priority DESC")
    List<AgentAccountAssignmentEntity> findActiveAssignments(
            @Param("agentType") String agentType,
            @Param("tenantId") String tenantId,
            @Param("appId") String appId);

    /** round_robin 타입의 활성 할당 */
    @Query("SELECT a FROM AgentAccountAssignmentEntity a JOIN FETCH a.account " +
            "WHERE a.isActive = true AND a.account.status = 'active' " +
            "AND a.account.agentType = :agentType " +
            "AND a.assignmentType = 'round_robin' " +
            "ORDER BY a.priority DESC")
    List<AgentAccountAssignmentEntity> findRoundRobinAssignments(@Param("agentType") String agentType);

    List<AgentAccountAssignmentEntity> findByAccountId(String accountId);
}
