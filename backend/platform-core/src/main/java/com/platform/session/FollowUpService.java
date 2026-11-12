package com.platform.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.domain.ConversationSessionEntity;
import com.platform.domain.UsageLogEntity;
import com.platform.llm.ConnectionAdapterFactory;
import com.platform.llm.LLMAdapterRegistry;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.adapter.RequestContext;
import com.platform.llm.model.LLMRequest;
import com.platform.llm.model.LLMResponse;
import com.platform.llm.model.ModelConfig;
import com.platform.llm.model.UnifiedMessage;
import com.platform.repository.ConversationMessageRepository;
import com.platform.repository.ConversationSessionRepository;
import com.platform.repository.UsageLogRepository;
import com.platform.service.PromptTemplateService;
import com.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * CR-175: 답이 끝난 뒤 후속 질문과 대화 제목을 만든다(PRD-372, BIZ-151).
 *
 * <p>대화 기록에 넣지 않으려고 대화 세션과 다른 세션 id 로 부른다. CLI 연결은 세션마다 실행기 작업자가
 * 따로 떠서 기억을 쌓으므로, 부른 뒤 그 작업자를 바로 정리한다.
 */
@Service
public class FollowUpService {

    private static final Logger log = LoggerFactory.getLogger(FollowUpService.class);
    static final String TEMPLATE_KEY = "widget.follow-ups";
    /** conversation_sessions.title 컬럼 길이. 모델이 지시보다 길게 써도 저장은 실패하지 않게 자른다. */
    static final int TITLE_COLUMN_LENGTH = 500;

    private final ChatWidgetSettingsService widgetSettings;
    private final ChatTurnLookup turnLookup;
    private final PromptTemplateService promptTemplateService;
    private final ConnectionAdapterFactory connectionAdapterFactory;
    private final LLMAdapterRegistry adapterRegistry;
    private final ConversationMessageRepository messageRepository;
    private final ConversationSessionRepository sessionRepository;
    private final UsageLogRepository usageLogRepository;
    private final ObjectMapper objectMapper;

    public FollowUpService(ChatWidgetSettingsService widgetSettings,
                           ChatTurnLookup turnLookup,
                           PromptTemplateService promptTemplateService,
                           ConnectionAdapterFactory connectionAdapterFactory,
                           LLMAdapterRegistry adapterRegistry,
                           ConversationMessageRepository messageRepository,
                           ConversationSessionRepository sessionRepository,
                           UsageLogRepository usageLogRepository,
                           ObjectMapper objectMapper) {
        this.widgetSettings = widgetSettings;
        this.turnLookup = turnLookup;
        this.promptTemplateService = promptTemplateService;
        this.connectionAdapterFactory = connectionAdapterFactory;
        this.adapterRegistry = adapterRegistry;
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.usageLogRepository = usageLogRepository;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> generate(String requesterRef, String sessionId, String turnIdText, String connectionId) {
        if (!widgetSettings.followUpsEnabled()) {
            throw new ChatRejectedException(HttpStatus.CONFLICT, "follow_ups_disabled");
        }
        ChatTurnLookup.Turn turn = turnLookup.find(requesterRef, sessionId, turnIdText);
        ConversationSessionEntity session = turn.session();
        boolean firstAnswer = !messageRepository.existsBySessionIdAndRoleAndSeqLessThanAndDeletedAtIsNull(
                sessionId, "assistant", turn.firstSeq());
        boolean needTitle = firstAnswer
                && !ConversationSessionEntity.TITLE_SOURCE_USER.equals(session.getTitleSource());
        int count = widgetSettings.followUpsCount();

        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("count", count);
        vars.put("need_title", needTitle ? "yes" : "no");
        vars.put("question", turn.question());
        vars.put("answer", turn.answer());
        String prompt = promptTemplateService.render(TEMPLATE_KEY, vars);
        if (prompt == null) {
            log.error("Prompt template {} is missing for tenant {}", TEMPLATE_KEY, TenantContext.getTenantId());
            throw failed();
        }

        String connId = connectionId != null && !connectionId.isBlank()
                ? connectionId : connectionAdapterFactory.findDefaultCliConnectionForCurrentTenant();
        String model = widgetSettings.followUpsModel();
        if (model == null) {
            model = connId != null ? connectionAdapterFactory.defaultModel(connId)
                    : connectionAdapterFactory.platformDefaultModel();
        }
        String callSessionId = sessionId + ":follow-ups:" + turn.turnId();
        LLMResponse response = call(connId, model, prompt, callSessionId);
        saveUsage(requesterRef, sessionId, model, response);

        Parsed parsed = parse(response.textContent(), count);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("follow_ups", parsed.followUps());
        if (needTitle && !parsed.title().isBlank()) {
            session.setTitle(parsed.title());
            session.setTitleSource(ConversationSessionEntity.TITLE_SOURCE_GENERATED);
            sessionRepository.save(session);
            body.put("title", parsed.title());
        }
        return body;
    }

    private LLMResponse call(String connId, String model, String prompt, String callSessionId) {
        LLMAdapter adapter;
        try {
            adapter = connId != null ? connectionAdapterFactory.getAdapter(connId) : adapterRegistry.getAdapter(model);
        } catch (RuntimeException e) {
            log.warn("Follow-ups adapter unavailable (connection={}, model={}): {}", connId, model, e.getMessage());
            throw failed();
        }
        // 생각은 끈다. 연결 기본값이 켬이면 생각부터 해서 짧은 JSON 하나에 시간 한도를 넘긴다
        // (개발기 CLI 연결에서 켬 11초, 끔 4초).
        LLMRequest request = new LLMRequest(model, List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, prompt)),
                null, ModelConfig.defaults().withThinkingOverride(false), false, callSessionId);

        // CLI 어댑터는 chat() 안에서 끝날 때까지 막히므로 시간 한도를 걸려면 따로 돌린다.
        // 실행기 고르기에 쓰는 테넌트, 토큰 user_ref, 에이전트 id 를 그 스레드로 옮긴다.
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
        try {
            return future.get(widgetSettings.followUpsTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("Follow-ups call timed out after {} ms (session={})", widgetSettings.followUpsTimeoutMs(), callSessionId);
            throw failed();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failed();
        } catch (Exception e) {
            log.warn("Follow-ups call failed (session={}): {}", callSessionId, e.getMessage());
            throw failed();
        } finally {
            try {
                adapter.cleanupSession(callSessionId);
            } catch (RuntimeException e) {
                log.warn("Follow-ups session cleanup failed (session={}): {}", callSessionId, e.getMessage());
            }
        }
    }

    Parsed parse(String text, int count) {
        if (text == null) throw failed();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            log.warn("Follow-ups reply is not JSON: {}", abbreviate(text));
            throw failed();
        }
        try {
            JsonNode root = objectMapper.readTree(text.substring(start, end + 1));
            List<String> followUps = new ArrayList<>();
            JsonNode items = root.path("follow_ups");
            if (items.isArray()) {
                for (JsonNode item : items) {
                    String q = item.asText("").strip();
                    if (!q.isEmpty() && followUps.size() < count) followUps.add(q);
                }
            }
            String title = root.path("title").asText("").strip();
            if (title.length() > TITLE_COLUMN_LENGTH) title = title.substring(0, TITLE_COLUMN_LENGTH);
            return new Parsed(followUps, title);
        } catch (Exception e) {
            log.warn("Follow-ups reply JSON parse failed: {}", e.getMessage());
            throw failed();
        }
    }

    private void saveUsage(String userId, String sessionId, String model, LLMResponse response) {
        try {
            UsageLogEntity usage = new UsageLogEntity();
            usage.setUserId(userId);
            usage.setSessionId(sessionId);
            usage.setModel(model);
            if (response.usage() != null) {
                usage.setInputTokens(response.usage().inputTokens());
                usage.setOutputTokens(response.usage().outputTokens());
            }
            usage.setCostUsd(BigDecimal.valueOf(response.costUsd()));
            usageLogRepository.save(usage);
        } catch (Exception e) {
            log.warn("Failed to save follow-ups usage log: {}", e.getMessage());
        }
    }

    private static String abbreviate(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200);
    }

    private static ChatRejectedException failed() {
        return new ChatRejectedException(HttpStatus.BAD_GATEWAY, "follow_ups_failed");
    }

    record Parsed(List<String> followUps, String title) {}
}
