package com.platform.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import com.platform.domain.ChatDocumentVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ChatDocumentVersionRepository extends JpaRepository<ChatDocumentVersionEntity, Long> {

    @Query("SELECT v FROM ChatDocumentVersionEntity v WHERE v.documentId = :documentId AND v.version = :version AND v.deletedAt IS NULL")
    Optional<ChatDocumentVersionEntity> findLive(UUID documentId, int version);

    @Query("SELECT v FROM ChatDocumentVersionEntity v WHERE v.documentId IN :documentIds AND v.deletedAt IS NULL ORDER BY v.version ASC")
    List<ChatDocumentVersionEntity> findLiveByDocumentIds(Collection<UUID> documentIds);

    @Query("SELECT COALESCE(MAX(v.version), 0) FROM ChatDocumentVersionEntity v WHERE v.documentId = :documentId AND v.deletedAt IS NULL")
    int findLiveMaxVersion(UUID documentId);

    /** BIZ-202: 되감기로 지운 답이 만든 판. 1판(ORIGINAL)은 고치기로 만든 판이 아니라 남긴다. */
    @Query("SELECT v FROM ChatDocumentVersionEntity v WHERE v.turnId IN :turnIds AND v.deletedAt IS NULL "
            + "AND v.madeBy = com.platform.domain.ChatDocumentVersionEntity.MadeBy.CHAT_EDIT")
    List<ChatDocumentVersionEntity> findLiveEditsByTurnIds(Collection<UUID> turnIds);

    @Modifying
    @Query("UPDATE ChatDocumentVersionEntity v SET v.deletedAt = :ts WHERE v.id IN :ids")
    int softDelete(Collection<Long> ids, OffsetDateTime ts);

    /** CR-204: 삭제 표시 여부와 관계없이 문서들의 판 전부(파일 경로를 모으려고). */
    List<ChatDocumentVersionEntity> findByDocumentIdIn(Collection<UUID> documentIds);

    /** CR-204 BIZ-295: 삭제 표시 뒤 보관 기간이 지난 판. */
    List<ChatDocumentVersionEntity> findByDeletedAtBefore(OffsetDateTime cutoff, Pageable pageable);

    /** CR-204 BIZ-292: 대화 완전 삭제. 그 대화 문서들의 판을 모두 지운다. */
    @Modifying
    @Query("DELETE FROM ChatDocumentVersionEntity v WHERE v.documentId IN :documentIds")
    int purgeByDocumentIds(@Param("documentIds") Collection<UUID> documentIds);

    @Modifying
    @Query("DELETE FROM ChatDocumentVersionEntity v WHERE v.id IN :ids")
    int deleteByIds(@Param("ids") Collection<Long> ids);
}
