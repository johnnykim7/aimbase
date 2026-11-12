package com.platform.session;

import com.platform.auth.UserPrincipal;
import com.platform.domain.ConversationMemoryEntity;
import com.platform.domain.WidgetUserPreferenceEntity;
import com.platform.llm.model.UnifiedMessage;
import com.platform.orchestrator.ChatRequest;
import com.platform.repository.ConversationMemoryRepository;
import com.platform.repository.WidgetUserPreferenceRepository;
import com.platform.service.PromptTemplateService;
import com.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * CR-179: 위젯 사용자의 내 선호, 자동 기억 목록, 마지막에 선택한 값(PRD-440~442)과 대화에 넣을 덩어리(BIZ-181).
 * 주인은 위젯 토큰 user_ref 다(BIZ-180).
 */
@Service
public class WidgetPreferenceService {

    private static final Logger log = LoggerFactory.getLogger(WidgetPreferenceService.class);

    static final String CONTEXT_TEMPLATE_KEY = "widget.user-memory";
    /** widget_user_preferences.last_model 칸 길이. */
    static final int LAST_MODEL_MAX_CHARS = 100;
    /** 틀의 빈 칸 자리. 모델에게 가는 말이라 틀과 같은 영어로 둔다. */
    static final String EMPTY_SLOT = "(none)";

    private final WidgetUserPreferenceRepository preferenceRepository;
    private final ConversationMemoryRepository memoryRepository;
    private final ChatWidgetSettingsService widgetSettings;
    private final PromptTemplateService promptTemplateService;

    public WidgetPreferenceService(WidgetUserPreferenceRepository preferenceRepository,
                                   ConversationMemoryRepository memoryRepository,
                                   ChatWidgetSettingsService widgetSettings,
                                   PromptTemplateService promptTemplateService) {
        this.preferenceRepository = preferenceRepository;
        this.memoryRepository = memoryRepository;
        this.widgetSettings = widgetSettings;
        this.promptTemplateService = promptTemplateService;
    }

    /**
     * 기억과 선호의 주인. 위젯 토큰이고 user_ref 가 있을 때만 그 값이다(BIZ-180).
     * 로그인 사용자는 대화 주인은 있어도 기억과 선호는 쓰지 않으므로 위젯 토큰인지 따로 본다.
     */
    public static String ownerOf(UserPrincipal principal) {
        return ChatOwner.isWidget(principal) ? ChatOwner.of(principal) : null;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> preferences(String owner) {
        requireAvailable(owner);
        return toBody(preferenceRepository.findById(owner).orElse(null));
    }

    /** 본문에 온 칸만 바꾼다. null 을 보내면 그 칸을 비운다. */
    @Transactional
    public Map<String, Object> updatePreferences(String owner, Map<String, Object> body) {
        requireAvailable(owner);
        WidgetUserPreferenceEntity pref = preferenceRepository.findById(owner)
                .orElseGet(() -> new WidgetUserPreferenceEntity(owner));
        if (body != null) {
            if (body.containsKey("instructions")) {
                String text = optionalText(body, "instructions");
                if (text != null && text.length() > widgetSettings.preferencesMaxChars()) {
                    throw new ChatRejectedException(HttpStatus.BAD_REQUEST, "preferences_too_long");
                }
                pref.setInstructions(text == null || text.isEmpty() ? null : text);
            }
            if (body.containsKey("memory_enabled")) {
                if (!(body.get("memory_enabled") instanceof Boolean on)) {
                    throw new IllegalArgumentException("memory_enabled must be true or false");
                }
                pref.setMemoryEnabled(on);
            }
            if (body.containsKey("last_model")) {
                String model;
                try {
                    model = optionalText(body, "last_model");
                } catch (IllegalArgumentException e) {
                    throw new ChatRejectedException(HttpStatus.BAD_REQUEST, "model_invalid");
                }
                if (model != null && model.length() > LAST_MODEL_MAX_CHARS) {
                    throw new ChatRejectedException(HttpStatus.BAD_REQUEST, "model_invalid");
                }
                pref.setLastModel(model == null || model.isEmpty() ? null : model);
            }
            if (body.containsKey("last_thinking")) {
                Object value = body.get("last_thinking");
                if (value != null && !(value instanceof Boolean)) {
                    throw new IllegalArgumentException("last_thinking must be true, false or null");
                }
                pref.setLastThinking((Boolean) value);
            }
        }
        return toBody(preferenceRepository.save(pref));
    }

    /** 자동 기억 목록, 오래된 것부터. 자동 기억을 꺼도 보이고 지울 수 있다(BIZ-184). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> memories(String owner) {
        requireAvailable(owner);
        return findMemories(owner).stream().map(WidgetPreferenceService::toMemoryBody).toList();
    }

    @Transactional
    public void deleteMemory(String owner, String idText) {
        requireAvailable(owner);
        UUID id;
        try {
            id = UUID.fromString(idText);
        } catch (IllegalArgumentException e) {
            throw notFound();
        }
        ConversationMemoryEntity memory = memoryRepository.findByIdAndUserIdAndMemoryTypeAndScope(
                id, owner, MemoryLayer.USER_PROFILE.name(), MemoryScope.PRIVATE.name()).orElseThrow(WidgetPreferenceService::notFound);
        memoryRepository.delete(memory);
    }

    @Transactional
    public long deleteAllMemories(String owner) {
        requireAvailable(owner);
        return memoryRepository.deleteByUserIdAndMemoryTypeAndScope(
                owner, MemoryLayer.USER_PROFILE.name(), MemoryScope.PRIVATE.name());
    }

    /** 자동 기억 뽑기를 돌릴지. 행이 없으면 켬이다. */
    @Transactional(readOnly = true)
    public boolean autoMemoryOn(String owner) {
        if (owner == null || !widgetSettings.memoryEnabled()) return false;
        return preferenceRepository.findById(owner).map(WidgetUserPreferenceEntity::isMemoryEnabled).orElse(true);
    }

    public List<ConversationMemoryEntity> findMemories(String owner) {
        return memoryRepository.findByUserIdAndMemoryTypeAndScopeOrderByCreatedAtAsc(
                owner, MemoryLayer.USER_PROFILE.name(), MemoryScope.PRIVATE.name());
    }

    /**
     * 대화에 넣을 내 선호와 자동 기억 한 덩어리(BIZ-181, BIZ-184).
     * 주인이 없거나, 테넌트 스위치가 꺼졌거나, 넣을 것이 없으면 null.
     */
    @Transactional(readOnly = true)
    public String contextBlock(String owner) {
        if (owner == null || !widgetSettings.memoryEnabled()) return null;
        WidgetUserPreferenceEntity pref = preferenceRepository.findById(owner).orElse(null);
        String instructions = pref != null ? pref.getInstructions() : null;
        boolean memoryOn = pref == null || pref.isMemoryEnabled();
        List<String> memories = memoryOn
                ? findMemories(owner).stream().map(ConversationMemoryEntity::getContent).toList()
                : List.of();
        boolean noInstructions = instructions == null || instructions.isBlank();
        if (noInstructions && memories.isEmpty()) return null;

        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("instructions", noInstructions ? EMPTY_SLOT : instructions);
        vars.put("memories", memories.isEmpty() ? EMPTY_SLOT
                : memories.stream().map(m -> "- " + m).collect(Collectors.joining("\n")));
        String text = promptTemplateService.render(CONTEXT_TEMPLATE_KEY, vars);
        if (text == null) {
            log.error("Prompt template {} is missing for tenant {}", CONTEXT_TEMPLATE_KEY, TenantContext.getTenantId());
        }
        return text;
    }

    /** 이번 대화 요청의 기억 주인. 위젯 토큰 user_ref 가 없으면 null(BIZ-180). */
    public static String memoryOwner(ChatRequest request) {
        return request.widgetTurn() != null ? request.widgetTurn().memoryOwner() : null;
    }

    /**
     * 내 선호와 자동 기억을 이번 질문 몫 시스템 지시로 마지막 질문 바로 앞에 넣는다(BIZ-181).
     * 화면 대화(스트림)는 맥락 조립을 거치지 않으므로 두 경로가 모두 여기를 부른다.
     * 프로젝트 지시문(CR-178)은 같은 자리에 뒤이어 들어와 기억 다음에 놓인다.
     * 대화 기록에는 남지 않고, 읽다가 실패해도 대화는 그대로 간다.
     */
    public List<UnifiedMessage> attach(List<UnifiedMessage> messages, ChatRequest request) {
        String owner = memoryOwner(request);
        if (owner == null) return messages;
        String block;
        try {
            block = contextBlock(owner);
        } catch (Exception e) {
            log.warn("Widget memory context failed: {}", e.getMessage());
            return messages;
        }
        if (block == null || block.isBlank()) return messages;
        List<UnifiedMessage> out = new ArrayList<>(messages);
        int at = out.size();
        for (int i = out.size() - 1; i >= 0; i--) {
            if (out.get(i).role() == UnifiedMessage.Role.USER) {
                at = i;
                break;
            }
        }
        out.add(at, UnifiedMessage.turnScopedSystem(block));
        return out;
    }

    private void requireAvailable(String owner) {
        if (owner == null) throw new ChatRejectedException(HttpStatus.FORBIDDEN, "memory_unavailable");
        if (!widgetSettings.memoryEnabled()) throw new ChatRejectedException(HttpStatus.CONFLICT, "memory_disabled");
    }

    private Map<String, Object> toBody(WidgetUserPreferenceEntity pref) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instructions", pref != null && pref.getInstructions() != null ? pref.getInstructions() : "");
        body.put("instructions_max_chars", widgetSettings.preferencesMaxChars());
        body.put("memory_enabled", pref == null || pref.isMemoryEnabled());
        body.put("last_model", pref != null ? pref.getLastModel() : null);
        body.put("last_thinking", pref != null ? pref.getLastThinking() : null);
        return body;
    }

    private static Map<String, Object> toMemoryBody(ConversationMemoryEntity memory) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", memory.getId().toString());
        body.put("content", memory.getContent());
        body.put("created_at", memory.getCreatedAt());
        return body;
    }

    private static String optionalText(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value == null) return null;
        if (!(value instanceof String text)) throw new IllegalArgumentException(field + " must be a string");
        return text.strip();
    }

    private static ChatRejectedException notFound() {
        return new ChatRejectedException(HttpStatus.NOT_FOUND, "memory_not_found");
    }
}
