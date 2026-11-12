package com.platform.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.attachment.AttachmentKind;
import com.platform.attachment.AttachmentService;
import com.platform.config.PlatformSettingsService;
import com.platform.image.ImageConnectionResolver;
import com.platform.image.ImageGenerationSettings;
import com.platform.document.DocumentRenderer;
import com.platform.llm.ConnectionAdapterFactory;
import com.platform.llm.LLMAdapterRegistry;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.orchestrator.FileCreateToolGate;
import com.platform.repository.ConnectionRepository;
import com.platform.share.ChatShareSettings;
import com.platform.speech.SttLocalConnections;
import com.platform.speech.WidgetTtsService;
import com.platform.speech.voice.VoiceRelayHandler;
import com.platform.speech.voice.VoiceTicketService;
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
    static final String MEMORY_ENABLED_KEY = "widget.memory-enabled";
    static final String MEMORY_MODEL_KEY = "widget.memory-model";
    static final String MEMORY_TIMEOUT_KEY = "widget.memory-timeout-ms";
    static final String MEMORY_MAX_ITEMS_KEY = "widget.memory-max-items";
    static final String MEMORY_ITEM_MAX_CHARS_KEY = "widget.memory-item-max-chars";
    static final String PREFERENCES_MAX_CHARS_KEY = "widget.preferences-max-chars";

    private final PlatformSettingsService platformSettings;
    private final ConnectionAdapterFactory connectionAdapterFactory;
    private final LLMAdapterRegistry adapterRegistry;
    private final ConnectionRepository connectionRepository;
    private final ObjectMapper objectMapper;
    private final ImageConnectionResolver imageConnectionResolver;
    private final ImageGenerationSettings imageSettings;
    private final AttachmentService attachmentService;
    private final FileCreateToolGate fileCreateToolGate;
    private final DocumentRenderer documentRenderer;
    private final ChatShareSettings shareSettings;
    private final SttLocalConnections sttConnections;

    public ChatWidgetSettingsService(PlatformSettingsService platformSettings,
                                     ConnectionAdapterFactory connectionAdapterFactory,
                                     LLMAdapterRegistry adapterRegistry,
                                     ConnectionRepository connectionRepository,
                                     ObjectMapper objectMapper,
                                     ImageConnectionResolver imageConnectionResolver,
                                     ImageGenerationSettings imageSettings,
                                     AttachmentService attachmentService,
                                     FileCreateToolGate fileCreateToolGate,
                                     DocumentRenderer documentRenderer,
                                     ChatShareSettings shareSettings,
                                     SttLocalConnections sttConnections) {
        this.platformSettings = platformSettings;
        this.connectionAdapterFactory = connectionAdapterFactory;
        this.adapterRegistry = adapterRegistry;
        this.connectionRepository = connectionRepository;
        this.objectMapper = objectMapper;
        this.imageConnectionResolver = imageConnectionResolver;
        this.imageSettings = imageSettings;
        this.attachmentService = attachmentService;
        this.fileCreateToolGate = fileCreateToolGate;
        this.documentRenderer = documentRenderer;
        this.shareSettings = shareSettings;
        this.sttConnections = sttConnections;
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
        body.put("attachments", attachments());
        // CR-182: 위젯이 파일 만들기를 요청에 싣고, 저장과 내보내기 형식, 공유 단추를 이것으로 정한다.
        body.put("file_create_enabled", fileCreateToolGate.enabled());
        body.put("export_formats", exportFormats());
        body.put("share_enabled", shareSettings.usable());
        body.put("share_ttl_days", shareSettings.ttlDays());
        body.put("document_binary_edit_supported", binaryDocumentEditSupported(connectionId));
        // CR-186: 답 읽어 주기와 음성 대화(PRD-580, PRD-581)
        boolean ttsEnabled = !"false".equalsIgnoreCase(platformSettings.getString(WidgetTtsService.KEY_ENABLED, "true"));
        body.put("tts_enabled", ttsEnabled);
        body.put("tts_max_chars", platformSettings.getInt(WidgetTtsService.KEY_MAX_CHARS, 300));
        body.put("voice_chat_enabled", voiceChatEnabled());
        body.put("voice_end_silence_ms", platformSettings.getInt(VoiceTicketService.KEY_END_SILENCE_MS, 1200));
        body.put("voice_speech_level", platformSettings.getDouble(VoiceTicketService.KEY_SPEECH_LEVEL, 0.02));
        body.put("voice_max_utterance_seconds", platformSettings.getInt(VoiceRelayHandler.KEY_MAX_UTTERANCE, 60));
        body.put("voice_idle_end_seconds", platformSettings.getInt(VoiceTicketService.KEY_IDLE_END_SECONDS, 120));
        return body;
    }

    /** 음성 대화를 켜 두었고 그 테넌트에 맥 받아쓰기 서버 연결이 있을 때만 위젯이 단추를 보인다. */
    public boolean voiceChatEnabled() {
        if ("false".equalsIgnoreCase(platformSettings.getString(VoiceTicketService.KEY_ENABLED, "true"))) return false;
        return sttConnections.findActive().isPresent();
    }

    /** CR-183: 파일 선택 창이 받는 확장자와 종류별 크기 상한(T3-2, BIZ-220). 위젯이 올리기 전에 같은 기준으로 거른다. */
    Map<String, Object> attachments() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accept", AttachmentKind.acceptedExtensions());
        out.put("max_bytes", attachmentService.maxBytesByGroup());
        return out;
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
     * CR-181 BIZ-203: CR-182 원본 없이 만들어진 파일(pptx 등)을 대화로 고칠 수 있는지.
     * 모델이 작업 폴더의 파일을 직접 고쳐야 하므로 실행기(CLI) 연결만 된다. 어댑터 선택과 같은 순서로 본다.
     */
    public boolean binaryDocumentEditSupported(String connectionId) {
        String connId = effectiveConnection(connectionId);
        return connId != null && connectionAdapterFactory.isCliConnection(connId);
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

    /** PDF 는 글꼴이 있을 때만 준다(BIZ-211). 워드와 마크다운은 늘 된다. */
    public List<String> exportFormats() {
        return documentRenderer.pdfAvailable() ? List.of("pdf", "docx", "md") : List.of("docx", "md");
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

    /** CR-179 테넌트 스위치(BIZ-184). 꺼지면 내 선호, 자동 기억, 마지막에 선택한 값을 모두 쓰지 않는다. */
    public boolean memoryEnabled() {
        return Boolean.parseBoolean(platformSettings.getString(MEMORY_ENABLED_KEY, "true"));
    }

    /** 비어 있으면 null. 그때는 그 대화 연결 기본 모델을 쓴다. */
    public String memoryModel() {
        String model = platformSettings.getString(MEMORY_MODEL_KEY, "");
        return model == null || model.isBlank() ? null : model.trim();
    }

    public long memoryTimeoutMs() {
        return platformSettings.getLong(MEMORY_TIMEOUT_KEY, 15000L);
    }

    public int memoryMaxItems() {
        return platformSettings.getInt(MEMORY_MAX_ITEMS_KEY, 30);
    }

    public int memoryItemMaxChars() {
        return platformSettings.getInt(MEMORY_ITEM_MAX_CHARS_KEY, 200);
    }

    public int preferencesMaxChars() {
        return platformSettings.getInt(PREFERENCES_MAX_CHARS_KEY, 1500);
    }
}
