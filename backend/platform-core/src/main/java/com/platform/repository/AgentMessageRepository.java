package com.platform.repository;

import com.platform.domain.AgentMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * CR-034 PRD-228: 에이전트 메시지 Repository.
 */
@Repository
public interface AgentMessageRepository extends JpaRepository<AgentMessageEntity, UUID> {

    /** 특정 에이전트의 수신 메시지 (최신순) */
    List<AgentMessageEntity> findByToAgentIdOrderByCreatedAtDesc(String toAgentId);

    /** 특정 에이전트의 읽지 않은 메시지 */
    List<AgentMessageEntity> findByToAgentIdAndIsReadFalseOrderByCreatedAtAsc(String toAgentId);

    /** 세션 내 전체 메시지 (시간순) */
    List<AgentMessageEntity> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    /** 브로드캐스트 + 특정 수신자 메시지 */
    @Query("SELECT m FROM AgentMessageEntity m WHERE m.sessionId = :sessionId " +
           "AND (m.toAgentId = :agentId OR m.toAgentId = '*') ORDER BY m.createdAt ASC")
    List<AgentMessageEntity> findMessagesForAgent(String sessionId, String agentId);

    /** 읽음 처리 */
    @Modifying
    @Query("UPDATE AgentMessageEntity m SET m.isRead = true WHERE m.toAgentId = :agentId AND m.isRead = false")
    int markAllAsRead(String agentId);
}
