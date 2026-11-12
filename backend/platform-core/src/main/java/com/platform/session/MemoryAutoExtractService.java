package com.platform.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.domain.ConversationMemoryEntity;
import com.platform.domain.UsageLogEntity;
import com.platform.llm.ConnectionAdapterFactory;
import com.platform.llm.LLMAdapterRegistry;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.adapter.RequestContext;
import com.platform.llm.model.LLMRequest;
import com.platform.llm.model.LLMResponse;
import com.platform.llm.model.ModelConfig;
import com.platform.llm.model.UnifiedMessage;
import com.platform.orchestrator.ChatRequest;
import com.platform.repository.ConversationMemoryRepository;
import com.platform.repository.UsageLogRepository;
import com.platform.service.PromptTemplateService;
import com.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * CR-179: 위젯 대화 질문에서 그 사람의 선호와 자기 정보를 뽑아 자동 기억으로 남긴다(PRD-441, BIZ-182, BIZ-183).
 *
 * <p>응답이 정상으로 끝나 저장된 뒤 응답과 따로 돈다. 실패해도 대화에는 영향이 없다.
 * 대화 기록에 넣지 않으려고 대화 세션과 다른 세션 id 로 부르고, CLI 연결은 부른 뒤 그 작업자를 바로 정리한다.
 */
@Service
public class MemoryAutoExtractService {

    private static final Logger log = LoggerFactory.getLogger(MemoryAutoExtractService.class);

    static final String TEMPLATE_KEY = "widget.memory-extract";
    /** 같은 사람의 질문이 겹쳐 끝나면 같은 기억을 두 번 넣거나 개수 한도를 넘기므로, 반영 단계만 사람별로 줄 세운다. */
    private static final int LOCK_STRIPES = 64;

    private final ChatWidgetSettingsService widgetSettings;
    private final WidgetPreferenceService preferenceService;
    private final ConversationMemoryRepository memoryRepository;
    private final PromptTemplateService promptTemplateService;
    private final ConnectionAdapterFactory connectionAdapterFactory;
    private final LLMAdapterRegistry adapterRegistry;
    private final UsageLogRepository usageLogRepository;
    private final ObjectMapper objectMapper;
    private final ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];

    public MemoryAutoExtractService(ChatWidgetSettingsService widgetSettings,
                                    WidgetPreferenceService preferenceService,
                                    ConversationMemoryRepository memoryRepository,
                                    PromptTemplateService promptTemplateService,
                                    ConnectionAdapterFactory connectionAdapterFactory,
                                    LLMAdapterRegistry adapterRegistry,
                                    UsageLogRepository usageLogRepository,
                                    ObjectMapper objectMapper) {
        this.widgetSettings = widgetSettings;
        this.preferenceService = preferenceService;
        this.memoryRepository = memoryRepository;
        this.promptTemplateService = promptTemplateService;
        this.connectionAdapterFactory = connectionAdapterFactory;
        this.adapterRegistry = adapterRegistry;
        this.usageLogRepository = usageLogRepository;
        this.objectMapper = objectMapper;
        for (int i = 0; i < LOCK_STRIPES; i++) locks[i] = new ReentrantLock();
    }

    /**
     * 정상으로 끝난 위젯 대화 한 번 뒤에 부른다. 기억 주인이 없거나 질문 글이 없으면 아무것도 하지 않는다(BIZ-180).
     */
    public void extractAfterTurn(ChatRequest request, String sessionId, UUID turnId) {
        String owner = WidgetPreferenceService.memoryOwner(request);
        String question = request.widgetTurn() != null ? request.widgetTurn().questionText() : null;
        if (owner == null || question == null || question.isBlank()) return;
        if (!widgetSettings.memoryEnabled()) return;

        // 실행기 고르기에 쓰는 테넌트, 토큰 user_ref, 에이전트 id 를 뽑기 스레드로 옮긴다.
        String tenantId = TenantContext.getTenantId();
        String userRef = RequestContext.getUserRef();
        String agentId = RequestContext.getAgentId();
        String connectionId = request.connectionId();
        Thread.ofVirtual().start(() -> {
            try {
                if (tenantId != null) TenantContext.setTenantId(tenantId);
                if (userRef != null) RequestContext.setUserRef(userRef);
                if (agentId != null) RequestContext.setAgentId(agentId);
                extract(owner, question, sessionId, turnId, connectionId);
            } catch (Exception e) {
                log.warn("Widget memory extraction failed (session={}, turn={}): {}", sessionId, turnId, e.getMessage());
            } finally {
                RequestContext.clear();
                TenantContext.clear();
            }
        });
    }

    void extract(String owner, String question, String sessionId, UUID turnId, String connectionId) {
        if (!preferenceService.autoMemoryOn(owner)) return;

        List<ConversationMemoryEntity> current = preferenceService.findMemories(owner);
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("question", question);
        vars.put("memories", current.isEmpty() ? WidgetPreferenceService.EMPTY_SLOT
                : current.stream().map(m -> m.getId() + ": " + m.getContent()).collect(Collectors.joining("\n")));
        vars.put("max_chars", widgetSettings.memoryItemMaxChars());
        String prompt = promptTemplateService.render(TEMPLATE_KEY, vars);
        if (prompt == null) {
            log.error("Prompt template {} is missing for tenant {}", TEMPLATE_KEY, TenantContext.getTenantId());
            return;
        }

        String connId = connectionId != null && !connectionId.isBlank()
                ? connectionId : connectionAdapterFactory.findDefaultCliConnectionForCurrentTenant();
        String model = widgetSettings.memoryModel();
        if (model == null) {
            model = connId != null ? connectionAdapterFactory.defaultModel(connId)
                    : connectionAdapterFactory.platformDefaultModel();
        }
        String callSessionId = sessionId + ":memory:" + turnId;
        LLMResponse response = call(connId, model, prompt, callSessionId);
        if (response == null) return;
        saveUsage(owner, sessionId, model, response);

        Changes changes = parse(response.textContent());
        if (changes == null) return;
        apply(owner, changes);
    }

    private LLMResponse call(String connId, String model, String prompt, String callSessionId) {
        LLMAdapter adapter;
        try {
            adapter = connId != null ? connectionAdapterFactory.getAdapter(connId) : adapterRegistry.getAdapter(model);
        } catch (RuntimeException e) {
            log.warn("Widget memory adapter unavailable (connection={}, model={}): {}", connId, model, e.getMessage());
            return null;
        }
        // 생각은 끄고 도구는 주지 않는다. 짧은 JSON 하나라 생각부터 하면 시간 한도를 넘긴다.
        LLMRequest request = new LLMRequest(model, List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, prompt)),
                null, ModelConfig.defaults().withThinkingOverride(false), false, callSessionId);

        // CLI 어댑터는 chat() 안에서 끝날 때까지 막히므로 시간 한도를 걸려면 따로 돌린다.
        String tenantId = TenantContext.getTenantId();
        String userRef = RequestContext.getUserRef();
        String agentId = RequestContext.getAgentId();
        CompletableFuture<LLMResponse> future = CompletableFuture.supplyAsync(() -> {
            try {
                if (tenantId != null) TenantContext.setTenantId(tenantId);
                if (userRef != null) RequestContext.setUserRef(userRef);
                if (agentId != null) RequestContext.setAgentId(agentId);
                return adapter.chat(request).join();
            } finally {
                RequestContext.clear();
                TenantContext.clear();
            }
        }, task -> Thread.ofVirtual().start(task));
        long timeoutMs = widgetSettings.memoryTimeoutMs();
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("Widget memory call timed out after {} ms (session={})", timeoutMs, callSessionId);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("Widget memory call failed (session={}): {}", callSessionId, e.getMessage());
            return null;
        } finally {
            try {
                adapter.cleanupSession(callSessionId);
            } catch (RuntimeException e) {
                log.warn("Widget memory session cleanup failed (session={}): {}", callSessionId, e.getMessage());
            }
        }
    }

    /** {"add": [...], "remove": [...]} 를 읽는다. JSON 이 아니면 null 이고 아무것도 바꾸지 않는다. */
    Changes parse(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            log.warn("Widget memory reply is not JSON: {}", abbreviate(text));
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(text.substring(start, end + 1));
            return new Changes(texts(root.path("add")), new HashSet<>(texts(root.path("remove"))));
        } catch (Exception e) {
            log.warn("Widget memory reply JSON parse failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 뒤집은 기억을 지우고, 새 기억을 넣고, 개수 한도를 넘으면 오래된 것부터 지운다(BIZ-183).
     * remove 는 그 사람 기억 id 만 받는다. 같은 글(공백, 대소문자 무시)은 넣지 않는다.
     */
    void apply(String owner, Changes changes) {
        ReentrantLock lock = lockFor(owner);
        lock.lock();
        try {
            List<ConversationMemoryEntity> kept = new ArrayList<>(preferenceService.findMemories(owner));
            List<ConversationMemoryEntity> removed = kept.stream()
                    .filter(m -> changes.remove().contains(m.getId().toString()))
                    .toList();
            if (!removed.isEmpty()) {
                memoryRepository.deleteAll(removed);
                kept.removeAll(removed);
            }

            Set<String> seen = kept.stream().map(m -> normalize(m.getContent())).collect(Collectors.toCollection(HashSet::new));
            int maxChars = widgetSettings.memoryItemMaxChars();
            int added = 0;
            for (String raw : changes.add()) {
                String content = raw.strip();
                if (content.length() > maxChars) content = content.substring(0, maxChars).strip();
                if (content.isEmpty() || !seen.add(normalize(content))) continue;
                ConversationMemoryEntity entity = new ConversationMemoryEntity();
                entity.setUserId(owner);
                entity.setMemoryType(MemoryLayer.USER_PROFILE.name());
                entity.setScope(MemoryScope.PRIVATE.name());
                entity.setContent(content);
                kept.add(memoryRepository.save(entity));
                added++;
            }

            int maxItems = widgetSettings.memoryMaxItems();
            int overflow = kept.size() - maxItems;
            if (overflow > 0) {
                memoryRepository.deleteAll(new ArrayList<>(kept.subList(0, overflow)));
            }
            if (added > 0 || !removed.isEmpty() || overflow > 0) {
                log.info("Widget memory updated: added={}, removed={}, trimmed={}", added, removed.size(), Math.max(overflow, 0));
            }
        } finally {
            lock.unlock();
        }
    }

    private ReentrantLock lockFor(String owner) {
        String key = TenantContext.getTenantId() + ":" + owner;
        return locks[Math.floorMod(key.hashCode(), LOCK_STRIPES)];
    }

    private void saveUsage(String owner, String sessionId, String model, LLMResponse response) {
        try {
            UsageLogEntity usage = new UsageLogEntity();
            usage.setUserId(owner);
            usage.setSessionId(sessionId);
            usage.setModel(model);
            if (response.usage() != null) {
                usage.setInputTokens(response.usage().inputTokens());
                usage.setOutputTokens(response.usage().outputTokens());
            }
            usage.setCostUsd(BigDecimal.valueOf(response.costUsd()));
            usageLogRepository.save(usage);
        } catch (Exception e) {
            log.warn("Failed to save widget memory usage log: {}", e.getMessage());
        }
    }

    private static List<String> texts(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (item.isTextual() && !item.asText().isBlank()) out.add(item.asText());
            }
        }
        return out;
    }

    static String normalize(String text) {
        return text == null ? "" : text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String abbreviate(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200);
    }

    record Changes(List<String> add, Set<String> remove) {}
}
