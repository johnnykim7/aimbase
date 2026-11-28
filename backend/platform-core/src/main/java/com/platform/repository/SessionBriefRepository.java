package com.platform.repository;

import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
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

    /** CR-204 BIZ-292: 대화 완전 삭제. 삭제 표시 여부와 관계없이 그 대화의 행을 모두 지운다. */
    @Modifying
    @Query("DELETE FROM SessionBriefEntity b WHERE b.sessionId = :sessionId")
    int purgeBySessionId(@Param("sessionId") String sessionId);
}
