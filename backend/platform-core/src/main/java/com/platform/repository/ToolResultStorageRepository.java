package com.platform.repository;

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
}
