package com.platform.image;

import com.platform.config.PlatformSettingsService;
import org.springframework.stereotype.Component;

/** CR-184: 그림 만들기 운영 설정(global_config). 기본값은 master V87 시드와 같다. */
@Component
public class ImageGenerationSettings {

    static final String KEY_TIMEOUT_MS = "widget.image.timeout-ms";
    static final String KEY_MAX_PROMPT_CHARS = "widget.image.max-prompt-chars";
    static final String KEY_MAX_SOURCE_IMAGES = "widget.image.max-source-images";

    private static final long DEFAULT_TIMEOUT_MS = 120_000L;
    private static final int DEFAULT_MAX_PROMPT_CHARS = 4000;
    private static final int DEFAULT_MAX_SOURCE_IMAGES = 4;

    private final PlatformSettingsService settings;

    public ImageGenerationSettings(PlatformSettingsService settings) {
        this.settings = settings;
    }

    public long timeoutMs() {
        return settings.getLong(KEY_TIMEOUT_MS, DEFAULT_TIMEOUT_MS);
    }

    public int maxPromptChars() {
        return settings.getInt(KEY_MAX_PROMPT_CHARS, DEFAULT_MAX_PROMPT_CHARS);
    }

    public int maxSourceImages() {
        return settings.getInt(KEY_MAX_SOURCE_IMAGES, DEFAULT_MAX_SOURCE_IMAGES);
    }
}
