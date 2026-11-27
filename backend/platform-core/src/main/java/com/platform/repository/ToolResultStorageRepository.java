package com.platform.repository;

import org.springframework.data.repository.query.Param;
import com.platform.domain.ToolResultStorageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;

@Repository
public interface ToolResultStorageRepository extends JpaRepository<ToolResultStorageEntity, String> {

    @Modifying
    @Query("DELETE FROM ToolResultStorageEntity t WHERE t.expiresAt < :now")
    int deleteExpired(OffsetDateTime now);

    @Query("SELECT COALESCE(SUM(t.sizeBytes), 0) FROM ToolResultStorageEntity t")
    long totalSizeBytes();

    /** CR-204 BIZ-292: 대화 완전 삭제. 삭제 표시 여부와 관계없이 그 대화의 행을 모두 지운다. */
    @Modifying
    @Query("DELETE FROM ToolResultStorageEntity t WHERE t.sessionId = :sessionId")
    int purgeBySessionId(@Param("sessionId") String sessionId);
}
