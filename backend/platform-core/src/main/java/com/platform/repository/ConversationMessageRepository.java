package com.platform.repository;

import com.platform.domain.ConversationMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Repository
public interface ConversationMessageRepository extends JpaRepository<ConversationMessageEntity, UUID> {

    List<ConversationMessageEntity> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    @Modifying
    @Transactional
    void deleteBySessionId(String sessionId);
}
