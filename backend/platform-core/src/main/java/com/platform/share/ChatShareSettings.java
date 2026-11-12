package com.platform.share;

import com.platform.config.PlatformSettingsService;
import org.springframework.stereotype.Component;

/** 공유 링크 런타임 설정(CR-182 6절). 한 Aimbase 의 테넌트는 같은 공개 주소를 쓴다. */
@Component
public class ChatShareSettings {

    static final String ENABLED_KEY = "chat.share.enabled";
    static final String PUBLIC_BASE_URL_KEY = "chat.share.public-base-url";
    static final String TTL_DAYS_KEY = "chat.share.ttl-days";
    static final String MAX_TTL_DAYS_KEY = "chat.share.max-ttl-days";
    static final String OPEN_RATE_KEY = "chat.share.open-rate-per-minute";

    private final PlatformSettingsService settings;

    public ChatShareSettings(PlatformSettingsService settings) {
        this.settings = settings;
    }

    public boolean enabled() {
        return Boolean.parseBoolean(settings.getString(ENABLED_KEY, "true"));
    }

    /** 끝 빗금을 뗀 공개 주소. 비어 있으면 빈 글. */
    public String publicBaseUrl() {
        String url = settings.getString(PUBLIC_BASE_URL_KEY, "");
        url = url == null ? "" : url.strip();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    /** 공유를 만들 수 있다. 켜져 있고 공개 주소가 있다. */
    public boolean usable() {
        return enabled() && !publicBaseUrl().isEmpty();
    }

    public int ttlDays() {
        return Math.max(1, settings.getInt(TTL_DAYS_KEY, 7));
    }

    public int maxTtlDays() {
        return Math.max(ttlDays(), settings.getInt(MAX_TTL_DAYS_KEY, 30));
    }

    public int openRatePerMinute() {
        return settings.getInt(OPEN_RATE_KEY, 60);
    }
}
