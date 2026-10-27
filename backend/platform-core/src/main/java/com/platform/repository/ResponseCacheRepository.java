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

    /**
     * 만료 여부와 무관하게 찾는다. cache_key 에 UNIQUE(idx_response_cache_key)가 걸려 있어
     * 저장 시에는 만료된 행도 찾아 갱신해야 한다 — 조회(만료 제외)만 보고 새 행을 넣으면
     * 중복키로 터진다.
     */
    Optional<ResponseCacheEntity> findByCacheKey(String cacheKey);

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
