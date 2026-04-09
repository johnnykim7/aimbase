package com.platform.agent;

import com.platform.domain.AgentMessageEntity;
import com.platform.repository.AgentMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * CR-034 PRD-228: 에이전트 간 메시지 버스.
 *
 * 1:1 메시지, 브로드캐스트(*), 읽지 않은 메시지 조회 등을 제공.
 * 메시지는 DB에 영속 저장되어 세션 내 모든 에이전트가 조회 가능.
 */
@Service
public class AgentMessageBus {

    private static final Logger log = LoggerFactory.getLogger(AgentMessageBus.class);

    private final AgentMessageRepository messageRepository;

    public AgentMessageBus(AgentMessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    /**
     * 메시지 전송.
     *
     * @param sessionId   오케스트레이터 세션 ID
     * @param fromAgentId 발신자 ID (세션ID, 에이전트명, "orchestrator")
     * @param toAgentId   수신자 ID (세션ID, 에이전트명, "*" = broadcast)
     * @param messageType TEXT, COMMAND, RESULT, ERROR
     * @param content     메시지 본문
     * @param metadata    추가 메타데이터
     * @return 저장된 메시지 엔티티
     */
    @Transactional
    public AgentMessageEntity send(String sessionId, String fromAgentId, String toAgentId,
                                   String messageType, String content, Map<String, Object> metadata) {
        AgentMessageEntity msg = new AgentMessageEntity();
        msg.setSessionId(sessionId);
        msg.setFromAgentId(fromAgentId);
        msg.setToAgentId(toAgentId);
        msg.setMessageType(messageType != null ? messageType : "TEXT");
        msg.setContent(content);
        msg.setMetadata(metadata);

        AgentMessageEntity saved = messageRepository.save(msg);
        log.debug("Message sent: {} → {} (session={}, type={})",
                fromAgentId, toAgentId, sessionId, messageType);
        return saved;
    }

    /**
     * 특정 에이전트의 읽지 않은 메시지 조회.
     */
    @Transactional(readOnly = true)
    public List<AgentMessageEntity> getUnreadMessages(String agentId) {
        return messageRepository.findByToAgentIdAndIsReadFalseOrderByCreatedAtAsc(agentId);
    }

    /**
     * 세션 내 특정 에이전트 대상 메시지 조회 (브로드캐스트 포함).
     */
    @Transactional(readOnly = true)
    public List<AgentMessageEntity> getMessagesForAgent(String sessionId, String agentId) {
        return messageRepository.findMessagesForAgent(sessionId, agentId);
    }

    /**
     * 세션 내 전체 메시지 이력 조회.
     */
    @Transactional(readOnly = true)
    public List<AgentMessageEntity> getSessionMessages(String sessionId) {
        return messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    /**
     * 에이전트의 모든 읽지 않은 메시지를 읽음 처리.
     */
    @Transactional
    public int markAllAsRead(String agentId) {
        return messageRepository.markAllAsRead(agentId);
    }
}
