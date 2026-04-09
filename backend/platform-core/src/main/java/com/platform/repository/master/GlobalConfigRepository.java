package com.platform.repository.master;

import com.platform.domain.master.GlobalConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GlobalConfigRepository extends JpaRepository<GlobalConfigEntity, String> {
    Optional<GlobalConfigEntity> findByConfigKey(String configKey);
    List<GlobalConfigEntity> findByConfigKeyStartingWith(String prefix);
}
