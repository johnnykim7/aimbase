package com.platform.repository;

import com.platform.domain.ConversationMemoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ConversationMemoryRepository extends JpaRepository<ConversationMemoryEntity, UUID> {

    List<ConversationMemoryEntity> findBySessionIdAndMemoryTypeOrderByCreatedAtDesc(
            String sessionId, String memoryType);

    List<ConversationMemoryEntity> findByUserIdAndMemoryTypeOrderByCreatedAtDesc(
            String userId, String memoryType);

    List<ConversationMemoryEntity> findBySessionIdOrderByCreatedAtDesc(String sessionId);

    void deleteBySessionId(String sessionId);
}
