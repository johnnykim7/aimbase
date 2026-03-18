package com.platform.repository;

import com.platform.domain.ConversationSessionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConversationSessionRepository extends JpaRepository<ConversationSessionEntity, UUID> {

    Optional<ConversationSessionEntity> findBySessionId(String sessionId);

    @Query("SELECT s FROM ConversationSessionEntity s WHERE s.title LIKE %:query% ORDER BY s.updatedAt DESC")
    Page<ConversationSessionEntity> searchByTitle(String query, Pageable pageable);

    Page<ConversationSessionEntity> findAllByOrderByUpdatedAtDesc(Pageable pageable);

    @Modifying
    @Transactional
    void deleteBySessionId(String sessionId);
}
