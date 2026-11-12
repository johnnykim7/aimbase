package com.platform.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.config.PlatformSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 저장, 내보내기, 공유 사본에 들어가는 문구(CR-182). 런타임 설정 {@code chat.export.labels}(JSON 객체)가
 * 정하고, 빠진 낱말은 아래 기본값을 쓴다. 설정이 깨져 있어도 파일은 만들어져야 해서 기본값을 둔다.
 */
@Component
public class ChatExportLabels {

    public static final String SETTING_KEY = "chat.export.labels";

    public static final String QUESTION = "question";
    public static final String ANSWER = "answer";
    public static final String ATTACHMENT = "attachment";
    public static final String IMAGE = "image";
    public static final String UNTITLED = "untitled";
    public static final String EXPORTED_AT = "exported_at";
    public static final String ANSWERED_AT = "answered_at";
    public static final String MODEL = "model";
    public static final String MESSAGE_COUNT = "message_count";
    public static final String SHARED = "shared";
    public static final String EXPIRES = "expires";
    public static final String LINK_GONE = "link_gone";

    static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry(QUESTION, "질문"),
            Map.entry(ANSWER, "응답"),
            Map.entry(ATTACHMENT, "첨부"),
            Map.entry(IMAGE, "그림"),
            Map.entry(UNTITLED, "대화"),
            Map.entry(EXPORTED_AT, "내보낸 시각"),
            Map.entry(ANSWERED_AT, "응답 시각"),
            Map.entry(MODEL, "모델"),
            Map.entry(MESSAGE_COUNT, "메시지"),
            Map.entry(SHARED, "공유한 대화"),
            Map.entry(EXPIRES, "기한"),
            Map.entry(LINK_GONE, "링크가 없거나 끝났습니다"));

    private static final Logger log = LoggerFactory.getLogger(ChatExportLabels.class);

    private final PlatformSettingsService settings;
    private final ObjectMapper objectMapper;

    public ChatExportLabels(PlatformSettingsService settings, ObjectMapper objectMapper) {
        this.settings = settings;
        this.objectMapper = objectMapper;
    }

    public Map<String, String> current() {
        Map<String, String> out = new LinkedHashMap<>(DEFAULTS);
        String raw = settings.getString(SETTING_KEY, "");
        if (raw == null || raw.isBlank()) return out;
        try {
            Map<String, Object> custom = objectMapper.readValue(raw, new TypeReference<>() {});
            custom.forEach((k, v) -> {
                if (v != null && !v.toString().isBlank()) out.put(k, v.toString());
            });
        } catch (Exception e) {
            log.warn("Runtime setting {} is not a JSON object: {}", SETTING_KEY, e.getMessage());
        }
        return out;
    }
}
