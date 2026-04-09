package com.platform.agent;

import com.platform.domain.TodoEntity;
import com.platform.repository.TodoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CR-033 PRD-225: Todo 비즈니스 로직.
 * BIZ-055: 전체 교체 방식.
 */
@Component
public class TodoService {

    private static final Logger log = LoggerFactory.getLogger(TodoService.class);

    private final TodoRepository todoRepository;

    public TodoService(TodoRepository todoRepository) {
        this.todoRepository = todoRepository;
    }

    /**
     * BIZ-055: todos 전체 교체.
     * 기존 데이터를 삭제하고 새 데이터로 대체한다.
     */
    @Transactional
    public List<TodoEntity> replaceAll(String sessionId, List<Map<String, Object>> todosInput) {
        todoRepository.deleteBySessionId(sessionId);

        List<TodoEntity> saved = new ArrayList<>();
        for (int i = 0; i < todosInput.size(); i++) {
            Map<String, Object> input = todosInput.get(i);
            TodoEntity entity = new TodoEntity();
            entity.setSessionId(sessionId);
            entity.setContent((String) input.get("content"));
            entity.setActiveForm((String) input.getOrDefault("activeForm", ""));
            entity.setStatus((String) input.getOrDefault("status", "pending"));
            entity.setOrderIndex(i);
            saved.add(todoRepository.save(entity));
        }

        log.debug("Replaced {} todos for session {}", saved.size(), sessionId);
        return saved;
    }

    /** 세션의 Todo 목록 조회 */
    public List<TodoEntity> getTodos(String sessionId) {
        return todoRepository.findBySessionIdOrderByOrderIndexAsc(sessionId);
    }
}
