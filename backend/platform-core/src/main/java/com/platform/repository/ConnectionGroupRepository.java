package com.platform.repository;

import com.platform.domain.ConnectionGroupEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConnectionGroupRepository extends JpaRepository<ConnectionGroupEntity, String> {

    List<ConnectionGroupEntity> findByAdapter(String adapter);

    List<ConnectionGroupEntity> findByIsActiveTrue();

    Optional<ConnectionGroupEntity> findByAdapterAndIsDefaultTrue(String adapter);

    List<ConnectionGroupEntity> findByIsDefaultTrue();
}
