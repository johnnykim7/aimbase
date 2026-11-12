package com.platform.orchestrator;

import com.platform.domain.ChatProjectEntity;
import com.platform.llm.model.UnifiedMessage;
import com.platform.repository.ChatProjectRepository;
import com.platform.repository.ConversationSessionRepository;
import com.platform.service.PromptTemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CR-178 BIZ-172: 질문마다 붙는 시스템 지시를 이번 질문 앞에 넣는 한 곳.
 *
 * <p>순서는 화면 맥락, 검색 자료, 기억(CR-179), 프로젝트 지시문이다. 검색 자료와 화면 맥락은 이미 목록에 있고, 여기서
 * 마지막 사용자 메시지 바로 앞에 프로젝트 지시문을 넣는다. 대화 기록에는 남기지 않으므로 지시문을 고치면 다음
 * 질문부터 바뀐다.
 */
@Component
public class TurnInstructions {

    private static final Logger log = LoggerFactory.getLogger(TurnInstructions.class);

    static final String PROJECT_TEMPLATE_KEY = "widget.project-instructions";

    private final ConversationSessionRepository sessionRepository;
    private final ChatProjectRepository projectRepository;
    private final PromptTemplateService promptTemplateService;

    public TurnInstructions(ConversationSessionRepository sessionRepository,
                            ChatProjectRepository projectRepository,
                            PromptTemplateService promptTemplateService) {
        this.sessionRepository = sessionRepository;
        this.projectRepository = projectRepository;
        this.promptTemplateService = promptTemplateService;
    }

    /**
     * 질문마다 붙는 지시를 넣은 새 목록. 붙일 것이 없으면 받은 목록을 그대로 돌려준다.
     */
    public List<UnifiedMessage> attach(List<UnifiedMessage> messages, String sessionId, ChatRequest request) {
        UnifiedMessage project = projectInstructions(sessionId, request);
        if (project == null) return messages;
        int lastUser = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == UnifiedMessage.Role.USER) {
                lastUser = i;
                break;
            }
        }
        if (lastUser < 0) return messages;
        List<UnifiedMessage> out = new ArrayList<>(messages);
        out.add(lastUser, project);
        return out;
    }

    private UnifiedMessage projectInstructions(String sessionId, ChatRequest request) {
        UUID projectId = projectIdOf(sessionId, request);
        if (projectId == null) return null;
        try {
            ChatProjectEntity project = projectRepository.findById(projectId).orElse(null);
            if (project == null || !project.hasInstructions()) return null;
            String text = promptTemplateService.render(PROJECT_TEMPLATE_KEY, Map.of(
                    "project_name", project.getName(),
                    "instructions", project.getInstructions()));
            if (text == null || text.isBlank()) {
                log.warn("Prompt template {} is missing; project instructions skipped (project={})",
                        PROJECT_TEMPLATE_KEY, projectId);
                return null;
            }
            return UnifiedMessage.turnScopedSystem(text);
        } catch (Exception e) {
            log.warn("Project instructions skipped for session {}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /** 세션의 프로젝트. 첫 질문이라 세션이 아직 없으면 요청에 실린 프로젝트. */
    private UUID projectIdOf(String sessionId, ChatRequest request) {
        if (sessionId != null) {
            var session = sessionRepository.findBySessionId(sessionId);
            if (session.isPresent()) return session.get().getChatProjectId();
        }
        return request.widgetTurn() != null ? request.widgetTurn().chatProjectId() : null;
    }
}
