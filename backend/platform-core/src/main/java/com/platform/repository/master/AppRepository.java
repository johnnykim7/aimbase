package com.platform.repository.master;

import com.platform.domain.master.AppEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AppRepository extends JpaRepository<AppEntity, String> {
    List<AppEntity> findByStatus(String status);
}
