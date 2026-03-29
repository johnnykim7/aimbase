package com.platform.repository;

import com.platform.domain.ResponseCacheEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface ResponseCacheRepository extends JpaRepository<ResponseCacheEntity, UUID> {

    Optional<ResponseCacheEntity> findByCacheKeyAndExpiresAtAfter(String cacheKey, OffsetDateTime now);

    @Modifying
    @Query("DELETE FROM ResponseCacheEntity e WHERE e.expiresAt < :now")
    int deleteExpired(OffsetDateTime now);
}
