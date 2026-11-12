package com.platform.repository;

import com.platform.domain.ChatAttachmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface ChatAttachmentRepository extends JpaRepository<ChatAttachmentEntity, UUID> {

    List<ChatAttachmentEntity> findBySessionId(String sessionId);

    long countBySessionIdAndExpiresAtAfter(String sessionId, OffsetDateTime now);

    /** CR-183: 세션에 보관 중인 데이터 파일이 있는지(BIZ-222). */
    boolean existsBySessionIdAndMediaTypeInAndExpiresAtAfter(String sessionId, java.util.Collection<String> mediaTypes,
                                                             OffsetDateTime now);

    List<ChatAttachmentEntity> findTop100ByExpiresAtBefore(OffsetDateTime now);
}
