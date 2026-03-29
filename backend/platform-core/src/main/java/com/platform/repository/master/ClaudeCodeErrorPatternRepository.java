package com.platform.repository.master;

import com.platform.domain.master.ClaudeCodeErrorPatternEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClaudeCodeErrorPatternRepository extends JpaRepository<ClaudeCodeErrorPatternEntity, Long> {

    /** 활성 패턴을 priority 내림차순으로 조회 (매칭 우선순위 순) */
    List<ClaudeCodeErrorPatternEntity> findByIsActiveTrueOrderByPriorityDesc();
}
