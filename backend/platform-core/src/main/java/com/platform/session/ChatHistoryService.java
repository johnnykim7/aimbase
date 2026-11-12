package com.platform.session;

import com.platform.config.PlatformSettingsService;
import com.platform.domain.ChatAttachmentEntity;
import com.platform.domain.ConversationMessageEntity;
import com.platform.domain.ConversationSessionEntity;
import com.platform.domain.MessageFeedbackEntity;
import com.platform.repository.ChatAttachmentRepository;
import com.platform.repository.ChatGeneratedImageRepository;
import com.platform.repository.ConversationMessageRepository;
import com.platform.repository.ConversationSessionRepository;
import com.platform.repository.MessageFeedbackRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * CR-178: 위젯 지난 대화 목록, 이어보기, 이름 바꾸기, 옮기기, 삭제(PRD-420 ~ PRD-422, PRD-424).
 *
 * <p>대상은 요청자가 주인인 세션뿐이고 남의 세션은 없는 것으로 보고 404 다(BIZ-170). 삭제는 관리용 대화 API 와
 * 이 서비스 하나를 같이 쓴다.
 */
@Service
public class ChatHistoryService {

    static final int DEFAULT_PAGE_SIZE = 30;
    static final int MAX_PAGE_SIZE = 100;
    static final int DEFAULT_TURNS_MAX = 100;
    static final int TITLE_MAX = 500;
    /** 목록 조건의 「프로젝트에 없는 대화」. */
    public static final String NO_PROJECT = "none";

    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";

    private final ConversationSessionRepository sessionRepository;
    private final ConversationMessageRepository messageRepository;
    private final ChatAttachmentRepository attachmentRepository;
    private final MessageFeedbackRepository feedbackRepository;
    private final ChatProjectService projectService;
    private final CancellationRegistry cancellationRegistry;
    private final SessionStore sessionStore;
    private final PlatformSettingsService settings;
    private final ChatGeneratedImageRepository imageRepository;

    public ChatHistoryService(ConversationSessionRepository sessionRepository,
                              ConversationMessageRepository messageRepository,
                              ChatAttachmentRepository attachmentRepository,
                              MessageFeedbackRepository feedbackRepository,
                              ChatProjectService projectService,
                              CancellationRegistry cancellationRegistry,
                              SessionStore sessionStore,
                              PlatformSettingsService settings,
                              ChatGeneratedImageRepository imageRepository) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.attachmentRepository = attachmentRepository;
        this.feedbackRepository = feedbackRepository;
        this.projectService = projectService;
        this.cancellationRegistry = cancellationRegistry;
        this.sessionStore = sessionStore;
        this.settings = settings;
        this.imageRepository = imageRepository;
    }

    public Page<Map<String, Object>> list(String owner, String chatProjectId, String query, int page, Integer size) {
        int pageSize = size != null ? size : settings.getInt("widget.history-page-size", DEFAULT_PAGE_SIZE);
        pageSize = Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
        PageRequest pageable = PageRequest.of(Math.max(0, page), pageSize);

        String titlePattern = null;
        int projectFilter = 0;
        String projectParam = null;
        if (query != null && !query.isBlank()) {
            // 검색어를 주면 프로젝트 조건을 보지 않고 전부에서 찾는다
            titlePattern = "%" + escapeLike(query.trim()) + "%";
        } else if (NO_PROJECT.equals(chatProjectId)) {
            projectFilter = 2;
        } else if (chatProjectId != null && !chatProjectId.isBlank()) {
            projectFilter = 1;
            projectParam = projectService.requireOwned(owner, chatProjectId).getId().toString();
        }
        return sessionRepository.findWidgetHistory(owner, projectFilter, projectParam, titlePattern, pageable)
                .map(ChatHistoryService::listRow);
    }

    /** 이어보기. 질문 하나부터 다음 질문 앞까지가 한 묶음이다(BIZ-174). */
    public Map<String, Object> messages(String owner, String sessionId) {
        ConversationSessionEntity session = requireOwned(owner, sessionId);
        List<ConversationMessageEntity> rows = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);

        // 답 번호의 첫 순번. 화면 맥락까지 한 묶음으로 되감기 위해서다(CR-175 BIZ-148)
        Map<UUID, Integer> firstSeqByTurn = new HashMap<>();
        for (ConversationMessageEntity m : rows) {
            if (m.getTurnId() != null) firstSeqByTurn.merge(m.getTurnId(), m.getSeq(), Math::min);
        }

        List<TurnRows> turns = new ArrayList<>();
        TurnRows current = null;
        for (ConversationMessageEntity m : rows) {
            if (!ConversationMessageEntity.TYPE_TEXT.equals(m.getMessageType())
                    && !ConversationMessageEntity.TYPE_TOOL_USE.equals(m.getMessageType())) {
                continue;
            }
            if (ROLE_USER.equals(m.getRole()) && ConversationMessageEntity.TYPE_TEXT.equals(m.getMessageType())) {
                current = new TurnRows(m);
                turns.add(current);
            } else if (ROLE_ASSISTANT.equals(m.getRole()) && current != null) {
                current.answers.add(m);
            }
        }

        int turnsMax = settings.getInt("widget.history-turns-max", DEFAULT_TURNS_MAX);
        boolean truncated = turnsMax > 0 && turns.size() > turnsMax;
        if (truncated) turns = new ArrayList<>(turns.subList(turns.size() - turnsMax, turns.size()));

        Map<UUID, MessageFeedbackEntity> feedback = feedbackFor(owner, turns);
        Map<UUID, ChatAttachmentEntity> attachments = attachmentsFor(turns);
        OffsetDateTime now = OffsetDateTime.now();

        List<Map<String, Object>> turnBodies = new ArrayList<>();
        for (TurnRows t : turns) {
            UUID turnId = t.question.getTurnId();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("turn_id", turnId != null ? turnId.toString() : null);
            body.put("user_seq", turnId != null ? firstSeqByTurn.get(turnId) : null);
            body.put("question", questionBody(t.question, attachments, now));
            body.put("answer", answerBody(t.answers));
            MessageFeedbackEntity fb = turnId != null ? feedback.get(turnId) : null;
            body.put("feedback", fb != null ? feedbackBody(fb) : null);
            turnBodies.add(body);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("session_id", session.getSessionId());
        result.put("title", session.getTitle());
        result.put("chat_project_id", session.getChatProjectId() != null ? session.getChatProjectId().toString() : null);
        result.put("truncated", truncated);
        result.put("turns", turnBodies);
        return result;
    }

    /** 준 칸만 바꾼다. 제목을 바꾸면 자동 제목이 덮지 않게 USER 로 적는다(CR-175 BIZ-151). */
    @Transactional
    public Map<String, Object> update(String owner, String sessionId, Map<String, Object> request) {
        ConversationSessionEntity session = requireOwned(owner, sessionId);
        Map<String, Object> body = request != null ? request : Map.of();
        if (body.containsKey("title")) {
            String title = body.get("title") instanceof String s ? s.trim() : "";
            if (title.isEmpty() || title.length() > TITLE_MAX) {
                throw new ChatRejectedException(HttpStatus.BAD_REQUEST, "session_invalid:title");
            }
            session.setTitle(title);
            session.setTitleSource(ConversationSessionEntity.TITLE_SOURCE_USER);
        }
        if (body.containsKey("chat_project_id")) {
            Object value = body.get("chat_project_id");
            session.setChatProjectId(value == null ? null
                    : projectService.requireOwned(owner, value.toString()).getId());
        }
        // 마지막 활동 시각은 저장할 때 엔티티가 채우므로 먼저 써 넣고 그 값을 돌려준다
        return listRow(sessionRepository.saveAndFlush(session));
    }

    @Transactional
    public void delete(String owner, String sessionId) {
        deleteSession(requireOwned(owner, sessionId));
    }

    /**
     * 답이 돌고 있으면 멈추고, 세션과 메시지에 지운 표시를 하고, Redis 대화 기록을 비운다.
     * 관리용 대화 삭제(ConversationController)도 이 메서드를 쓴다.
     */
    @Transactional
    public void deleteSession(ConversationSessionEntity session) {
        String sessionId = session.getSessionId();
        cancellationRegistry.cancel(sessionId);
        OffsetDateTime now = OffsetDateTime.now();
        session.setDeletedAt(now);
        sessionRepository.save(session);
        messageRepository.softDeleteBySessionId(sessionId, now);
        // CR-184 BIZ-231: 그 대화에서 만든 그림도 지운 표시. 파일은 남긴다
        imageRepository.softDeleteBySessionId(sessionId, now);
        sessionStore.clearSession(sessionId);
    }

    private ConversationSessionEntity requireOwned(String owner, String sessionId) {
        if (owner == null || sessionId == null || sessionId.isBlank()) throw sessionNotFound();
        return sessionRepository.findBySessionId(sessionId)
                .filter(s -> owner.equals(s.getUserId()))
                .orElseThrow(ChatHistoryService::sessionNotFound);
    }

    private Map<UUID, MessageFeedbackEntity> feedbackFor(String owner, List<TurnRows> turns) {
        Set<UUID> turnIds = new LinkedHashSet<>();
        for (TurnRows t : turns) {
            if (t.question.getTurnId() != null) turnIds.add(t.question.getTurnId());
        }
        Map<UUID, MessageFeedbackEntity> byTurn = new HashMap<>();
        if (turnIds.isEmpty()) return byTurn;
        for (MessageFeedbackEntity fb : feedbackRepository.findByTurnIdInAndUserRef(turnIds, owner)) {
            byTurn.put(fb.getTurnId(), fb);
        }
        return byTurn;
    }

    private Map<UUID, ChatAttachmentEntity> attachmentsFor(List<TurnRows> turns) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (TurnRows t : turns) {
            for (Map<String, Object> a : displayAttachments(t.question)) {
                UUID id = parseUuid(a.get("attachment_id"));
                if (id != null) ids.add(id);
            }
        }
        Map<UUID, ChatAttachmentEntity> byId = new HashMap<>();
        if (ids.isEmpty()) return byId;
        for (ChatAttachmentEntity a : attachmentRepository.findAllById(ids)) {
            byId.put(a.getId(), a);
        }
        return byId;
    }

    private static Map<String, Object> questionBody(ConversationMessageEntity q,
                                                    Map<UUID, ChatAttachmentEntity> attachments,
                                                    OffsetDateTime now) {
        Map<String, Object> display = q.getDisplay();
        String text = display != null && display.get("text") instanceof String s ? s : q.getContent();
        List<Map<String, Object>> attachmentBodies = new ArrayList<>();
        for (Map<String, Object> a : displayAttachments(q)) {
            UUID id = parseUuid(a.get("attachment_id"));
            ChatAttachmentEntity row = id != null ? attachments.get(id) : null;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("attachment_id", id != null ? id.toString() : null);
            // 만료로 첨부 행이 지워져도 질문 때 남긴 이름과 종류는 보여준다(사용자가 무엇을 올렸는지 알 수 있게)
            body.put("filename", a.get("filename"));
            body.put("media_type", a.get("media_type"));
            body.put("available", row != null && row.getExpiresAt() != null && row.getExpiresAt().isAfter(now));
            attachmentBodies.add(body);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", text);
        body.put("created_at", q.getCreatedAt());
        body.put("attachments", attachmentBodies);
        return body;
    }

    private static Map<String, Object> answerBody(List<ConversationMessageEntity> answers) {
        if (answers.isEmpty()) return null;
        StringBuilder text = new StringBuilder();
        List<Map<String, Object>> toolUses = new ArrayList<>();
        String model = null;
        OffsetDateTime createdAt = null;
        for (ConversationMessageEntity a : answers) {
            if (ConversationMessageEntity.TYPE_TEXT.equals(a.getMessageType()) && a.getContent() != null
                    && !a.getContent().isEmpty()) {
                if (!text.isEmpty()) text.append("\n\n");
                text.append(a.getContent());
            }
            toolUses.addAll(toolUses(a));
            if (a.getModel() != null) model = a.getModel();
            createdAt = a.getCreatedAt();
        }
        String joined = text.toString();
        boolean stopped = ChatStopMarker.isStopped(joined);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", stopped ? ChatStopMarker.strip(joined) : joined);
        body.put("model", model);
        body.put("created_at", createdAt);
        body.put("stopped", stopped);
        body.put("tool_uses", toolUses);
        return body;
    }

    /** 답 줄에 남긴 도구 이름과, 도구 호출 줄이 있으면 그 이름. 도구 결과 본문은 돌려주지 않는다. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> toolUses(ConversationMessageEntity a) {
        List<Map<String, Object>> names = new ArrayList<>();
        if (a.getDisplay() != null && a.getDisplay().get("tool_uses") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && m.get("name") instanceof String name && !name.isBlank()) {
                    names.add(Map.of("name", name));
                }
            }
        }
        if (ConversationMessageEntity.TYPE_TOOL_USE.equals(a.getMessageType()) && a.getContentJson() != null) {
            for (Map<String, Object> block : a.getContentJson()) {
                if ("tool_use".equals(block.get("type")) && block.get("name") instanceof String name) {
                    names.add(Map.of("name", name));
                }
            }
        }
        return names;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> displayAttachments(ConversationMessageEntity q) {
        if (q.getDisplay() == null || !(q.getDisplay().get("attachments") instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        }
        return out;
    }

    private static Map<String, Object> feedbackBody(MessageFeedbackEntity fb) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rating", fb.getRating() != null ? fb.getRating().name() : null);
        body.put("reason_codes", fb.getReasonCodes() != null ? fb.getReasonCodes() : List.of());
        body.put("comment", fb.getComment());
        return body;
    }

    private static Map<String, Object> listRow(ConversationSessionEntity s) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("session_id", s.getSessionId());
        row.put("title", s.getTitle());
        row.put("chat_project_id", s.getChatProjectId() != null ? s.getChatProjectId().toString() : null);
        row.put("updated_at", s.getUpdatedAt());
        return row;
    }

    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static UUID parseUuid(Object value) {
        if (!(value instanceof String s) || s.isBlank()) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static ChatRejectedException sessionNotFound() {
        return new ChatRejectedException(HttpStatus.NOT_FOUND, "session_not_found");
    }

    private static final class TurnRows {
        final ConversationMessageEntity question;
        final List<ConversationMessageEntity> answers = new ArrayList<>();

        TurnRows(ConversationMessageEntity question) {
            this.question = question;
        }
    }
}
