package com.platform.repository;

import com.platform.domain.SessionBriefEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * CR-038 PRD-248: 세션 브리핑 레포지토리 (Tenant DB).
 */
@Repository
public interface SessionBriefRepository extends JpaRepository<SessionBriefEntity, String> {

    Optional<SessionBriefEntity> findTopBySessionIdOrderByCreatedAtDesc(String sessionId);

    void deleteBySessionId(String sessionId);
}
