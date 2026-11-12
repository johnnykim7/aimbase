package com.platform.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.config.PlatformSettingsService;
import com.platform.image.ImageConnectionResolver;
import com.platform.image.ImageGenerationSettings;
import com.platform.llm.ConnectionAdapterFactory;
import com.platform.llm.LLMAdapterRegistry;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.repository.ConnectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    static final String PREVIEW_HTML_SOURCES_KEY = "widget.preview-html-script-sources";

    private final PlatformSettingsService platformSettings;
    private final ConnectionAdapterFactory connectionAdapterFactory;
    private final LLMAdapterRegistry adapterRegistry;
    private final ConnectionRepository connectionRepository;
    private final ObjectMapper objectMapper;
    private final ImageConnectionResolver imageConnectionResolver;
    private final ImageGenerationSettings imageSettings;

    public ChatWidgetSettingsService(PlatformSettingsService platformSettings,
                                     ConnectionAdapterFactory connectionAdapterFactory,
                                     LLMAdapterRegistry adapterRegistry,
                                     ConnectionRepository connectionRepository,
                                     ObjectMapper objectMapper,
                                     ImageConnectionResolver imageConnectionResolver,
                                     ImageGenerationSettings imageSettings) {
        this.platformSettings = platformSettings;
        this.connectionAdapterFactory = connectionAdapterFactory;
        this.adapterRegistry = adapterRegistry;
        this.connectionRepository = connectionRepository;
        this.objectMapper = objectMapper;
        this.imageConnectionResolver = imageConnectionResolver;
        this.imageSettings = imageSettings;
    }

    /**
     * GET /api/v1/chat/widget-settings 본문. 그 테넌트에 없는 연결이면 404.
     * locale 은 아쉬워요 이유 이름의 언어다(CR-177, BIZ-161). 비면 기본 이름.
     */
    public Map<String, Object> settings(String connectionId, String locale) {
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
        body.put("feedback_reasons", feedbackReasons(locale));
        body.put("feedback_comment_max_chars", feedbackCommentMaxChars());
        body.put("follow_ups_enabled", followUpsEnabled());
        body.put("preview_html_script_sources", previewHtmlScriptSources());
        body.put("image_generation", imageGeneration(connectionId));
        // CR-178: user_ref 없는 위젯 토큰은 주인이 하나로 모여 남의 대화가 보이므로 목록과 프로젝트를 쓰지 못한다(BIZ-170)
        body.put("history_enabled", ChatOwner.current() != null);
        body.put("project_instructions_max_chars", platformSettings.getInt(
                "widget.project-instructions-max-chars", ChatProjectService.DEFAULT_INSTRUCTIONS_MAX));
        body.put("project_files_max_count", platformSettings.getInt(
                "widget.project-files-max-count", ChatProjectService.DEFAULT_FILES_MAX));
        return body;
    }

    /**
     * CR-184 PRD-544: 그 연결에 그림 연결이 있을 때만 위젯 + 메뉴에 「그림 만들기」 를 둔다(BIZ-230).
     * 글자 수와 바탕 그림 장수는 위젯이 보내기 전에 막고 오류 문구에 쓰는 상한이다.
     */
    private Map<String, Object> imageGeneration(String connectionId) {
        Map<String, Object> image = new LinkedHashMap<>();
        image.put("enabled", connectionId != null && !connectionId.isBlank()
                && imageConnectionResolver.isConfigured(connectionId));
        image.put("max_prompt_chars", imageSettings.maxPromptChars());
        image.put("max_source_images", imageSettings.maxSourceImages());
        return image;
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

    /** 아쉬워요 이유 [{code, label}], label 은 기본 이름. 설정 값이 JSON 목록이 아니면 빈 목록(이유 없이 평가만 받는다). */
    public List<Map<String, String>> feedbackReasons() {
        return feedbackReasons(null);
    }

    /**
     * 아쉬워요 이유 [{code, label}], label 은 항목 labels 의 그 언어 이름, 없으면 기본 이름(CR-177, BIZ-161).
     * labels 가 객체라 항목을 글 대 글 맵으로 읽으면 값 전체가 읽히지 않아, 평가 저장의 이유 검사까지 막힌다. 그래서 노드로 읽는다.
     * code 가 없는 항목은 저장할 수 없으므로 뺀다.
     */
    public List<Map<String, String>> feedbackReasons(String locale) {
        String raw = platformSettings.getString(FEEDBACK_REASONS_KEY, "[]");
        JsonNode root;
        try {
            root = objectMapper.readTree(raw);
        } catch (Exception e) {
            log.warn("Runtime setting {} is not a JSON list: {}", FEEDBACK_REASONS_KEY, e.getMessage());
            return List.of();
        }
        if (root == null || !root.isArray()) {
            log.warn("Runtime setting {} is not a JSON list", FEEDBACK_REASONS_KEY);
            return List.of();
        }
        String lang = language(locale);
        List<Map<String, String>> reasons = new ArrayList<>();
        for (JsonNode item : root) {
            String code = text(item.get("code"));
            if (code == null) continue;
            String label = text(item.get("label"));
            JsonNode labels = item.get("labels");
            if (lang != null && labels != null && labels.isObject()) {
                String localized = text(labels.get(lang));
                if (localized != null) label = localized;
            }
            Map<String, String> reason = new LinkedHashMap<>();
            reason.put("code", code);
            reason.put("label", label != null ? label : code);
            reasons.add(reason);
        }
        return reasons;
    }

    /** en-US, EN_us 처럼 지역이 붙은 값은 앞 언어만 본다(BIZ-160). 비면 null. */
    static String language(String locale) {
        if (locale == null || locale.isBlank()) return null;
        String lang = locale.trim().split("[-_]", 2)[0].toLowerCase(Locale.ROOT);
        return lang.isEmpty() ? null : lang;
    }

    private static String text(JsonNode node) {
        if (node == null || !node.isTextual()) return null;
        String value = node.asText();
        return value.isBlank() ? null : value;
    }

    /**
     * CR-180: HTML 실행 틀이 스크립트, 스타일, 글꼴, 그림을 불러올 수 있는 출처(BIZ-190).
     * 설정 값이 JSON 목록이 아니면 빈 목록이다. 그때 실행 틀은 바깥에서 아무것도 불러오지 않는다.
     */
    public List<String> previewHtmlScriptSources() {
        String raw = platformSettings.getString(PREVIEW_HTML_SOURCES_KEY, "[]");
        try {
            return objectMapper.readValue(raw, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Runtime setting {} is not a JSON list: {}", PREVIEW_HTML_SOURCES_KEY, e.getMessage());
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
