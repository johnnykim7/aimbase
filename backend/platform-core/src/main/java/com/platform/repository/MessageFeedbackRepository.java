package com.platform.repository;

import com.platform.domain.MessageFeedbackEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface MessageFeedbackRepository extends JpaRepository<MessageFeedbackEntity, Long>,
        JpaSpecificationExecutor<MessageFeedbackEntity> {

    /**
     * CR-175 BIZ-149: 평가 남기기와 바꾸기. 두 번 눌러도 (turn_id, user_ref) 한 줄만 남는다.
     * 처음 남긴 시각은 그대로 두고 마지막으로 바꾼 시각만 바꾼다.
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO message_feedback
            (session_id, turn_id, user_ref, rating, reason_codes, comment, model, created_at, updated_at)
        VALUES
            (:sessionId, CAST(:turnId AS UUID), :userRef, :rating, CAST(:reasonCodes AS JSONB), :comment, :model, NOW(), NOW())
        ON CONFLICT (turn_id, user_ref) DO UPDATE SET
            rating = EXCLUDED.rating,
            reason_codes = EXCLUDED.reason_codes,
            comment = EXCLUDED.comment,
            updated_at = NOW()
        """, nativeQuery = true)
    int upsert(@Param("sessionId") String sessionId,
               @Param("turnId") String turnId,
               @Param("userRef") String userRef,
               @Param("rating") String rating,
               @Param("reasonCodes") String reasonCodes,
               @Param("comment") String comment,
               @Param("model") String model);

    Optional<MessageFeedbackEntity> findByTurnIdAndUserRef(UUID turnId, String userRef);

    @Modifying
    @Transactional
    @Query("DELETE FROM MessageFeedbackEntity f WHERE f.turnId = :turnId AND f.userRef = :userRef")
    int deleteByTurnIdAndUserRef(@Param("turnId") UUID turnId, @Param("userRef") String userRef);
}
