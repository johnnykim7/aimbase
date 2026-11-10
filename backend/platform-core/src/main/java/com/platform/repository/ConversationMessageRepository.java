package com.platform.repository;

import com.platform.domain.ConversationMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface ConversationMessageRepository extends JpaRepository<ConversationMessageEntity, UUID> {

    // CR-046: 활성 메시지만 노출.
    // CR-083: createdAt → seq 정렬로 결정성 확보 (같은 ms 메시지 순서 뒤섞임 차단).
    @Query("SELECT m FROM ConversationMessageEntity m WHERE m.sessionId = :sessionId AND m.deletedAt IS NULL ORDER BY m.seq ASC")
    List<ConversationMessageEntity> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    long countBySessionId(String sessionId);

    /**
     * CR-083: 세션의 가장 큰 seq 반환 (없으면 -1). 새 메시지 INSERT 시 seq+1 계산용.
     * CR-175: 지우지 않은 행만 센다. 순번 고유도 지우지 않은 행만 대상이라 지운 순번은 다시 쓴다.
     */
    @Query(value = "SELECT COALESCE(MAX(seq), -1) FROM conversation_messages WHERE session_id = :sessionId AND deleted_at IS NULL",
           nativeQuery = true)
    int findMaxSeqBySessionId(@Param("sessionId") String sessionId);

    /**
     * CR-083: ON CONFLICT(session_id, seq) DO NOTHING 으로 idempotent INSERT.
     * race 동안 같은 (sessionId, seq) 가 동시 INSERT 되어도 한쪽만 성공하고 한쪽은 무해 skip.
     * content_json 은 JSONB 캐스팅 필수.
     * CR-175: 겹침 대상은 지우지 않은 행의 부분 고유 인덱스다. turn_id 는 null 바인딩 타입 문제를 피하려고 문자열로 받아 캐스팅한다.
     *
     * @return 넣은 행 수. 0 이면 그 순번을 다른 쓰기가 먼저 차지했다
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO conversation_messages
            (id, session_id, seq, role, message_type, content, content_json, tokens, model, created_at, turn_id)
        VALUES
            (:id, :sessionId, :seq, :role, :messageType, :content, CAST(:contentJson AS JSONB), :tokens, :model, :createdAt,
             CAST(:turnId AS UUID))
        ON CONFLICT (session_id, seq) WHERE deleted_at IS NULL DO NOTHING
        """, nativeQuery = true)
    int insertIdempotent(@Param("id") UUID id,
                         @Param("sessionId") String sessionId,
                         @Param("seq") int seq,
                         @Param("role") String role,
                         @Param("messageType") String messageType,
                         @Param("content") String content,
                         @Param("contentJson") String contentJson,
                         @Param("tokens") int tokens,
                         @Param("model") String model,
                         @Param("createdAt") OffsetDateTime createdAt,
                         @Param("turnId") String turnId);

    @Modifying
    @Transactional
    void deleteBySessionId(String sessionId);

    /** CR-046: Soft delete (UPDATE deleted_at). 부모 세션 삭제 시 cascade로 호출. */
    @Modifying
    @Transactional
    @Query("UPDATE ConversationMessageEntity m SET m.deletedAt = :ts, m.deleteReason = 'SESSION_DELETE' "
            + "WHERE m.sessionId = :sessionId AND m.deletedAt IS NULL")
    int softDeleteBySessionId(String sessionId, OffsetDateTime ts);

    /** CR-175 BIZ-148: 되감기. fromSeq 부터 뒤의 지우지 않은 메시지에 지운 표시를 한다. */
    @Modifying
    @Query("UPDATE ConversationMessageEntity m SET m.deletedAt = :ts, m.deleteReason = :reason "
            + "WHERE m.sessionId = :sessionId AND m.seq >= :fromSeq AND m.deletedAt IS NULL")
    int softDeleteFromSeq(String sessionId, int fromSeq, OffsetDateTime ts, String reason);

    /** CR-175: 답 번호 하나가 만든 메시지. 되감기로 지운 것도 포함한다(평가가 가리키는 답을 보여 주려고). */
    @Query("SELECT m FROM ConversationMessageEntity m WHERE m.sessionId = :sessionId AND m.turnId = :turnId ORDER BY m.seq ASC")
    List<ConversationMessageEntity> findBySessionIdAndTurnId(String sessionId, UUID turnId);

    /** CR-175: 답 번호들이 만든 메시지 (관리 화면 평가 목록에서 질문, 답 앞부분을 붙이려고). */
    @Query("SELECT m FROM ConversationMessageEntity m WHERE m.turnId IN :turnIds ORDER BY m.seq ASC")
    List<ConversationMessageEntity> findByTurnIdIn(java.util.Collection<UUID> turnIds);

    /** CR-175 BIZ-151: 이 순번 앞에 지우지 않은 답이 있는지. 없으면 세션의 첫 답이다. */
    boolean existsBySessionIdAndRoleAndSeqLessThanAndDeletedAtIsNull(String sessionId, String role, int seq);

    /** CR-049 PRD-303: 가장 최근 COMPACT_BOUNDARY 메시지. Pageable(size=1) 로 호출. CR-083: seq 정렬. */
    @Query("SELECT m FROM ConversationMessageEntity m " +
           "WHERE m.sessionId = :sessionId AND m.messageType = 'COMPACT_BOUNDARY' AND m.deletedAt IS NULL " +
           "ORDER BY m.seq DESC")
    List<ConversationMessageEntity> findLatestBoundary(String sessionId,
                                                        org.springframework.data.domain.Pageable pageable);

    /** CR-049 PRD-303: 지정 시각(=boundary 생성 시각) 이후 활성 메시지만 반환. CR-083: 정렬은 seq, 필터는 createdAt 유지. */
    @Query("SELECT m FROM ConversationMessageEntity m " +
           "WHERE m.sessionId = :sessionId AND m.deletedAt IS NULL AND m.createdAt >= :since " +
           "ORDER BY m.seq ASC")
    List<ConversationMessageEntity> findBySessionIdSince(String sessionId, OffsetDateTime since);
}
