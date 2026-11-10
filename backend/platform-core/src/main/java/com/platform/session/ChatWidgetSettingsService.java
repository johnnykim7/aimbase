package com.platform.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.config.PlatformSettingsService;
import com.platform.llm.ConnectionAdapterFactory;
import com.platform.llm.LLMAdapterRegistry;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.repository.ConnectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CR-175: 채팅 위젯이 뜰 때 받는 설정(모델 목록, 평가 이유, 후속 질문 사용)과 그 런타임 설정 읽기(PRD-371).
 */
@Service
public class ChatWidgetSettingsService {

    private static final Logger log = LoggerFactory.getLogger(ChatWidgetSettingsService.class);

    static final String FEEDBACK_REASONS_KEY = "widget.feedback-reasons";
    static final String FEEDBACK_COMMENT_MAX_KEY = "widget.feedback-comment-max-chars";
    static final String FOLLOW_UPS_ENABLED_KEY = "widget.follow-ups-enabled";
    static final String FOLLOW_UPS_COUNT_KEY = "widget.follow-ups-count";
    static final String FOLLOW_UPS_MODEL_KEY = "widget.follow-ups-model";
    static final String FOLLOW_UPS_TIMEOUT_KEY = "widget.follow-ups-timeout-ms";

    private final PlatformSettingsService platformSettings;
    private final ConnectionAdapterFactory connectionAdapterFactory;
    private final LLMAdapterRegistry adapterRegistry;
    private final ConnectionRepository connectionRepository;
    private final ObjectMapper objectMapper;

    public ChatWidgetSettingsService(PlatformSettingsService platformSettings,
                                     ConnectionAdapterFactory connectionAdapterFactory,
                                     LLMAdapterRegistry adapterRegistry,
                                     ConnectionRepository connectionRepository,
                                     ObjectMapper objectMapper) {
        this.platformSettings = platformSettings;
        this.connectionAdapterFactory = connectionAdapterFactory;
        this.adapterRegistry = adapterRegistry;
        this.connectionRepository = connectionRepository;
        this.objectMapper = objectMapper;
    }

    /** GET /api/v1/chat/widget-settings 본문. 그 테넌트에 없는 연결이면 404. */
    public Map<String, Object> settings(String connectionId) {
        List<Map<String, String>> models;
        String defaultModel;
        if (connectionId != null && !connectionId.isBlank()) {
            if (!connectionRepository.existsById(connectionId)) {
                throw new ChatRejectedException(HttpStatus.NOT_FOUND, "connection_not_found");
            }
            models = connectionAdapterFactory.selectableModels(connectionId);
            String configured = connectionAdapterFactory.defaultModel(connectionId);
            defaultModel = models.stream().anyMatch(m -> configured.equals(m.get("id")))
                    ? configured : models.get(0).get("id");
        } else {
            defaultModel = connectionAdapterFactory.platformDefaultModel();
            models = List.of(Map.of("id", defaultModel, "label", defaultModel));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("models", models);
        body.put("default_model", defaultModel);
        body.put("thinking_supported", thinkingSupported(connectionId, defaultModel));
        body.put("thinking_default", thinkingDefault(connectionId, defaultModel));
        body.put("feedback_reasons", feedbackReasons());
        body.put("feedback_comment_max_chars", feedbackCommentMaxChars());
        body.put("follow_ups_enabled", followUpsEnabled());
        return body;
    }

    /**
     * 이번 요청이 탈 어댑터가 깊게 생각하기를 요청마다 바꿀 수 있는지(BIZ-150).
     * OrchestratorEngine 의 어댑터 선택과 같은 순서로 본다: 연결, 테넌트 기본 CLI 연결, 모델.
     */
    public boolean thinkingSupported(String connectionId, String model) {
        LLMAdapter adapter = adapterFor(effectiveConnection(connectionId), model);
        return adapter != null && adapter.supportsThinkingOverride();
    }

    /**
     * 깊게 생각하기 단추의 처음 상태(BIZ-150). 연결 config 에 생각 설정이 있으면 그 값, 없으면 어댑터 기본이다.
     * 위젯은 단추 상태대로 on, off 를 늘 보내므로 이 값이 어긋나면 사용자가 손대지 않아도 동작이 바뀐다.
     */
    public boolean thinkingDefault(String connectionId, String model) {
        String connId = effectiveConnection(connectionId);
        if (connId != null) {
            Boolean configured = connectionAdapterFactory.resolveModelConfig(connId).explicitThinking();
            if (configured != null) return configured;
        }
        LLMAdapter adapter = adapterFor(connId, model);
        return adapter != null && adapter.thinksByDefault();
    }

    /** OrchestratorEngine 의 어댑터 선택과 같은 순서: 요청 연결, 테넌트 기본 CLI 연결. 둘 다 없으면 null(모델로 고른다). */
    private String effectiveConnection(String connectionId) {
        return connectionId != null && !connectionId.isBlank()
                ? connectionId : connectionAdapterFactory.findDefaultCliConnectionForCurrentTenant();
    }

    private LLMAdapter adapterFor(String connId, String model) {
        if (connId != null) return connectionAdapterFactory.getAdapter(connId);
        String modelId = model != null && !model.isBlank() && !"auto".equalsIgnoreCase(model)
                ? model : connectionAdapterFactory.platformDefaultModel();
        return adapterRegistry.hasAdapter(modelId) ? adapterRegistry.getAdapter(modelId) : null;
    }

    /** 아쉬워요 이유 [{code, label}]. 설정 값이 JSON 이 아니면 빈 목록(이유 없이 평가만 받는다). */
    public List<Map<String, String>> feedbackReasons() {
        String raw = platformSettings.getString(FEEDBACK_REASONS_KEY, "[]");
        try {
            return objectMapper.readValue(raw, new TypeReference<List<Map<String, String>>>() {});
        } catch (Exception e) {
            log.warn("Runtime setting {} is not a JSON list: {}", FEEDBACK_REASONS_KEY, e.getMessage());
            return List.of();
        }
    }

    public int feedbackCommentMaxChars() {
        return platformSettings.getInt(FEEDBACK_COMMENT_MAX_KEY, 500);
    }

    public boolean followUpsEnabled() {
        return Boolean.parseBoolean(platformSettings.getString(FOLLOW_UPS_ENABLED_KEY, "true"));
    }

    public int followUpsCount() {
        return platformSettings.getInt(FOLLOW_UPS_COUNT_KEY, 3);
    }

    /** 비어 있으면 null. 그때는 그 연결 기본 모델을 쓴다. */
    public String followUpsModel() {
        String model = platformSettings.getString(FOLLOW_UPS_MODEL_KEY, "");
        return model == null || model.isBlank() ? null : model.trim();
    }

    public long followUpsTimeoutMs() {
        return platformSettings.getLong(FOLLOW_UPS_TIMEOUT_KEY, 8000L);
    }
}
