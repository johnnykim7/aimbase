package com.platform.repository;

import com.platform.domain.TodoEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * CR-033 PRD-225: Todo Repository.
 */
@Repository
public interface TodoRepository extends JpaRepository<TodoEntity, UUID> {

    List<TodoEntity> findBySessionIdOrderByOrderIndexAsc(String sessionId);

    void deleteBySessionId(String sessionId);
}
