package com.platform.repository;

import com.platform.domain.ResponseCacheEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResponseCacheRepository extends JpaRepository<ResponseCacheEntity, UUID> {

    Optional<ResponseCacheEntity> findByCacheKeyAndExpiresAtAfter(String cacheKey, OffsetDateTime now);

    @Modifying
    @Query("DELETE FROM ResponseCacheEntity e WHERE e.expiresAt < :now")
    int deleteExpired(OffsetDateTime now);

    @Query("SELECT COALESCE(SUM(e.hitCount), 0) FROM ResponseCacheEntity e")
    long sumHitCount();

    /**
     * PRD-129: Semantic Match — pgvector 코사인 유사도 검색.
     * 유사도 >= threshold이고 만료되지 않은 캐시 중 가장 유사한 1건 반환.
     */
    /** PRD-129: 임베딩 벡터 업데이트 */
    @Modifying
    @Query(value = "UPDATE response_cache SET query_embedding = CAST(:embedding AS vector) WHERE id = :id",
            nativeQuery = true)
    void updateEmbedding(UUID id, String embedding);

    @Query(value = """
            SELECT * FROM response_cache
            WHERE query_embedding IS NOT NULL
              AND expires_at > NOW()
              AND 1 - (query_embedding <=> CAST(:embedding AS vector)) >= :threshold
            ORDER BY query_embedding <=> CAST(:embedding AS vector)
            LIMIT 1
            """, nativeQuery = true)
    Optional<ResponseCacheEntity> findSemanticallySimlar(String embedding, double threshold);
}
