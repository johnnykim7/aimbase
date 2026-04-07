package com.platform.session;

import com.platform.domain.SessionBriefEntity;
import com.platform.llm.LLMAdapterRegistry;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.LLMRequest;
import com.platform.llm.model.LLMResponse;
import com.platform.llm.model.UnifiedMessage;
import com.platform.repository.SessionBriefRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CR-038 PRD-248: 세션 브리핑 서비스.
 * 세션 이전 작업을 요약하여 브리핑을 생성/캐시/조회한다.
 * BIZ-068: 캐시 TTL 1시간. BIZ-069: 최근 50개 메시지.
 */
@Service
public class SessionBriefService {

    private static final Logger log = LoggerFactory.getLogger(SessionBriefService.class);
    private static final int MAX_MESSAGES = 50; // BIZ-069
    private static final long CACHE_TTL_MINUTES = 60; // BIZ-068
    private static final String BRIEF_MODEL = "anthropic/claude-haiku-4-5-20251001";

    private final SessionStore sessionStore;
    private final SessionBriefRepository briefRepository;
    private final LLMAdapterRegistry adapterRegistry;

    public SessionBriefService(SessionStore sessionStore,
                                SessionBriefRepository briefRepository,
                                LLMAdapterRegistry adapterRegistry) {
        this.sessionStore = sessionStore;
        this.briefRepository = briefRepository;
        this.adapterRegistry = adapterRegistry;
    }

    /**
     * 세션 브리핑을 조회한다. 캐시가 유효하면 캐시 반환.
     * @param sessionId 대상 세션
     * @param forceRefresh 캐시 무시하고 재생성
     * @return 브리핑 맵
     */
    public Map<String, Object> getBrief(String sessionId, boolean forceRefresh) {
        // 캐시 확인 (BIZ-068)
        if (!forceRefresh) {
            Optional<SessionBriefEntity> cached = briefRepository
                    .findTopBySessionIdOrderByCreatedAtDesc(sessionId);
            if (cached.isPresent()) {
                SessionBriefEntity brief = cached.get();
                if (brief.getCreatedAt().plusMinutes(CACHE_TTL_MINUTES).isAfter(OffsetDateTime.now())) {
                    log.debug("Brief cache hit for session {}", sessionId);
                    return brief.toMap();
                }
            }
        }

        // 메시지 로드 (BIZ-069: 최근 50개)
        List<UnifiedMessage> messages = sessionStore.getMessages(sessionId);
        if (messages == null || messages.isEmpty()) {
            return Map.of("session_id", sessionId, "summary", "메시지가 없는 세션입니다",
                    "key_decisions", List.of(), "pending_items", List.of());
        }

        int totalMessages = messages.size();
        if (totalMessages > MAX_MESSAGES) {
            messages = messages.subList(totalMessages - MAX_MESSAGES, totalMessages);
        }

        // LLM 요약 호출
        return generateBrief(sessionId, messages, totalMessages);
    }

    private Map<String, Object> generateBrief(String sessionId, List<UnifiedMessage> messages, int totalMessages) {
        String conversationText = formatMessages(messages);

        String systemPrompt = """
                You are a session briefing assistant. Analyze the conversation and produce a structured summary.
                Respond ONLY in this exact format (no markdown, no extra text):
                SUMMARY: <1-3 sentence overview of what was accomplished>
                DECISIONS: <comma-separated list of key decisions made, or "none">
                PENDING: <comma-separated list of unfinished items or next steps, or "none">
                """;

        try {
            LLMAdapter adapter = adapterRegistry.getAdapter(BRIEF_MODEL);
            LLMRequest request = new LLMRequest(
                    BRIEF_MODEL,
                    List.of(
                            UnifiedMessage.ofText(UnifiedMessage.Role.SYSTEM, systemPrompt),
                            UnifiedMessage.ofText(UnifiedMessage.Role.USER,
                                    "다음 대화를 분석하여 브리핑을 생성해주세요:\n\n" + conversationText)
                    )
            );

            LLMResponse response = adapter.chat(request).join();
            String responseText = response.textContent();

            return parseBriefResponse(sessionId, responseText, messages.size(), totalMessages);
        } catch (Exception e) {
            log.warn("Brief generation failed for session {}: {}", sessionId, e.getMessage());
            return fallbackBrief(sessionId, messages, totalMessages);
        }
    }

    private Map<String, Object> parseBriefResponse(String sessionId, String text,
                                                     int usedMessages, int totalMessages) {
        String summary = "세션 요약을 생성했습니다";
        List<String> decisions = List.of();
        List<String> pending = List.of();

        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.startsWith("SUMMARY:")) {
                summary = line.substring("SUMMARY:".length()).trim();
            } else if (line.startsWith("DECISIONS:")) {
                String val = line.substring("DECISIONS:".length()).trim();
                if (!"none".equalsIgnoreCase(val) && !val.isBlank()) {
                    decisions = List.of(val.split(",\\s*"));
                }
            } else if (line.startsWith("PENDING:")) {
                String val = line.substring("PENDING:".length()).trim();
                if (!"none".equalsIgnoreCase(val) && !val.isBlank()) {
                    pending = List.of(val.split(",\\s*"));
                }
            }
        }

        // DB 캐시 저장
        SessionBriefEntity entity = new SessionBriefEntity();
        entity.setSessionId(sessionId);
        entity.setSummary(summary);
        entity.setKeyDecisions(decisions);
        entity.setPendingItems(pending);
        entity.setMessageCount(usedMessages);
        entity.setModelUsed(BRIEF_MODEL);
        briefRepository.save(entity);

        return entity.toMap();
    }

    private Map<String, Object> fallbackBrief(String sessionId, List<UnifiedMessage> messages, int totalMessages) {
        String summary = totalMessages + "개 메시지가 있는 세션입니다 (최근 " + messages.size() + "개 분석)";
        SessionBriefEntity entity = new SessionBriefEntity();
        entity.setSessionId(sessionId);
        entity.setSummary(summary);
        entity.setKeyDecisions(List.of());
        entity.setPendingItems(List.of());
        entity.setMessageCount(messages.size());
        entity.setModelUsed("fallback");
        briefRepository.save(entity);
        return entity.toMap();
    }

    private String formatMessages(List<UnifiedMessage> messages) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (UnifiedMessage msg : messages) {
            if (count >= MAX_MESSAGES) break;
            sb.append("[").append(msg.role()).append("] ");
            if (msg.content() != null) {
                for (var block : msg.content()) {
                    if (block instanceof ContentBlock.Text textBlock) {
                        String text = textBlock.text();
                        if (text != null && text.length() > 500) {
                            text = text.substring(0, 500) + "...";
                        }
                        sb.append(text);
                    }
                }
            }
            sb.append("\n");
            count++;
        }
        return sb.toString();
    }
}
