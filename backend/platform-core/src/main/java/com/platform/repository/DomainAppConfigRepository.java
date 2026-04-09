package com.platform.repository;

import com.platform.domain.DomainAppConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * CR-029: 도메인 앱 설정 저장소.
 */
@Repository
public interface DomainAppConfigRepository extends JpaRepository<DomainAppConfigEntity, String> {

    Optional<DomainAppConfigEntity> findByDomainApp(String domainApp);
}
