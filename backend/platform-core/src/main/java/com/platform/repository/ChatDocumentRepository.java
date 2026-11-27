package com.platform.repository;

import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Modifying;
import com.platform.domain.ChatDocumentEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ChatDocumentRepository extends JpaRepository<ChatDocumentEntity, UUID> {

    @Query("SELECT d FROM ChatDocumentEntity d WHERE d.sessionId = :sessionId AND d.kind = com.platform.domain.ChatDocumentEntity.Kind.FILE AND d.name = :name")
    Optional<ChatDocumentEntity> findFile(String sessionId, String name);

    @Query("SELECT d FROM ChatDocumentEntity d WHERE d.sessionId = :sessionId AND d.kind = com.platform.domain.ChatDocumentEntity.Kind.BLOCK "
            + "AND d.originTurnId = :turnId AND d.blockIndex = :blockIndex")
    Optional<ChatDocumentEntity> findBlock(String sessionId, UUID turnId, int blockIndex);

    /** 판 번호를 매기는 동안 같은 문서의 다른 고치기가 같은 번호를 잡지 않게 문서 행을 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM ChatDocumentEntity d WHERE d.id = :id")
    Optional<ChatDocumentEntity> findForUpdate(UUID id);

    List<ChatDocumentEntity> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    /** CR-204 BIZ-292: 대화 완전 삭제. 삭제 표시 여부와 관계없이 그 대화의 행을 모두 지운다. */
    @Modifying
    @Query("DELETE FROM ChatDocumentEntity d WHERE d.sessionId = :sessionId")
    int purgeBySessionId(@Param("sessionId") String sessionId);
}
