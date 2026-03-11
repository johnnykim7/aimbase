package com.platform.repository;

import com.platform.domain.SchemaEntity;
import com.platform.domain.SchemaEntityId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SchemaRepository extends JpaRepository<SchemaEntity, SchemaEntityId> {
    @Query("SELECT s FROM SchemaEntity s WHERE s.pk.id = :id ORDER BY s.pk.version DESC")
    List<SchemaEntity> findAllVersionsById(String id);

    @Query("SELECT s FROM SchemaEntity s WHERE s.pk.id = :id AND s.pk.version = (SELECT MAX(s2.pk.version) FROM SchemaEntity s2 WHERE s2.pk.id = :id)")
    Optional<SchemaEntity> findLatestById(String id);
}
