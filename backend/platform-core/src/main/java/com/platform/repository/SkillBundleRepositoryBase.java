package com.platform.repository;

import com.platform.domain.SkillBundleBase;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * CR-147: 테넌트 전용과 플랫폼 공용 스킬 묶음 리포지토리가 같이 쓰는 질의.
 */
@NoRepositoryBean
public interface SkillBundleRepositoryBase<T extends SkillBundleBase> extends JpaRepository<T, UUID> {

    Optional<T> findByName(String name);

    /** 같은 이름을 동시에 올릴 때 판 번호가 겹치지 않게 묶음 행을 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from #{#entityName} b where b.name = :name")
    Optional<T> findForUpdateByName(@Param("name") String name);

    List<T> findAllByOrderByNameAsc();

    List<T> findByIsActiveTrueOrderByNameAsc();
}
