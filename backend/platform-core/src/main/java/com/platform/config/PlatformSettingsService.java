package com.platform.config;

import com.platform.domain.master.GlobalConfigEntity;
import com.platform.policy.AuditLogger;
import com.platform.repository.master.GlobalConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CR-040: 플랫폼 런타임 설정 서비스.
 *
 * global_config 테이블에서 설정값을 조회하며, 로컬 캐시(5분 TTL)로 DB 접근을 최소화한다.
 * 설정 변경 시 캐시 무효화 + 감사 로그를 기록한다.
 */
@Service
public class PlatformSettingsService {

    private static final Logger log = LoggerFactory.getLogger(PlatformSettingsService.class);
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L; // 5분

    private static final Set<String> CATEGORIES = Set.of("orchestrator", "session", "compaction");

    private final GlobalConfigRepository configRepository;
    private final AuditLogger auditLogger;

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public PlatformSettingsService(GlobalConfigRepository configRepository, AuditLogger auditLogger) {
        this.configRepository = configRepository;
        this.auditLogger = auditLogger;
    }

    /**
     * 설정값을 문자열로 조회. DB에 없으면 defaultValue 반환.
     */
    public String getString(String key, String defaultValue) {
        CacheEntry entry = cache.get(key);
        if (entry != null && !entry.isExpired()) {
            return entry.value;
        }

        try {
            Optional<GlobalConfigEntity> entity = configRepository.findByConfigKey(key);
            if (entity.isPresent()) {
                String value = entity.get().getConfigValue();
                cache.put(key, new CacheEntry(value));
                return value;
            }
        } catch (Exception e) {
            log.warn("Failed to read setting '{}', using default: {}", key, e.getMessage());
        }

        return defaultValue;
    }

    /**
     * 정수형 설정값 조회.
     */
    public int getInt(String key, int defaultValue) {
        String value = getString(key, null);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid int for key '{}': '{}', using default {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    /**
     * 실수형 설정값 조회.
     */
    public double getDouble(String key, double defaultValue) {
        String value = getString(key, null);
        if (value == null) return defaultValue;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid double for key '{}': '{}', using default {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    /**
     * 전체 설정을 카테고리별로 그룹핑하여 반환.
     */
    public Map<String, List<SettingItem>> getAllGrouped(String categoryFilter) {
        Map<String, List<SettingItem>> result = new LinkedHashMap<>();

        for (String category : CATEGORIES) {
            if (categoryFilter != null && !categoryFilter.equals(category)) continue;

            List<GlobalConfigEntity> entities = configRepository.findByConfigKeyStartingWith(category + ".");
            List<SettingItem> items = entities.stream()
                    .map(e -> new SettingItem(e.getConfigKey(), e.getConfigValue(),
                            e.getDescription(), e.getUpdatedBy(),
                            e.getUpdatedAt() != null ? e.getUpdatedAt().toString() : null))
                    .toList();
            if (!items.isEmpty()) {
                result.put(category, items);
            }
        }

        return result;
    }

    /**
     * 설정값 수정. 캐시 무효화 + 감사 로그.
     */
    public int updateSettings(List<SettingUpdateRequest> updates, String userId) {
        int updated = 0;

        for (SettingUpdateRequest req : updates) {
            Optional<GlobalConfigEntity> opt = configRepository.findByConfigKey(req.key());
            if (opt.isEmpty()) {
                log.warn("Setting key not found: {}", req.key());
                continue;
            }

            GlobalConfigEntity entity = opt.get();
            String oldValue = entity.getConfigValue();

            // 값 유효성 검증
            validateValue(req.key(), req.value());

            entity.setConfigValue(req.value());
            entity.setUpdatedBy(userId);
            configRepository.save(entity);

            // 캐시 무효화
            cache.remove(req.key());

            // 감사 로그
            auditLogger.log("platform_setting_change", req.key(), userId, null,
                    Map.of("old_value", oldValue, "new_value", req.value()), null);

            updated++;
        }

        return updated;
    }

    /**
     * 캐시 전체 무효화.
     */
    public void evictAll() {
        cache.clear();
    }

    private void validateValue(String key, String value) {
        if (key.endsWith("-percent")) {
            double pct = Double.parseDouble(value);
            if (pct < 0 || pct > 100) {
                throw new IllegalArgumentException("Percent value must be 0~100: " + key + "=" + value);
            }
        }
        if (key.contains("max-") || key.contains("budget") || key.contains("threshold")
                || key.contains("ttl") || key.contains("messages")) {
            int num = Integer.parseInt(value);
            if (num <= 0) {
                throw new IllegalArgumentException("Value must be positive: " + key + "=" + value);
            }
        }
    }

    // --- DTOs ---

    public record SettingItem(String key, String value, String description,
                              String updatedBy, String updatedAt) {}

    public record SettingUpdateRequest(String key, String value) {}

    private record CacheEntry(String value, long timestamp) {
        CacheEntry(String value) {
            this(value, Instant.now().toEpochMilli());
        }
        boolean isExpired() {
            return Instant.now().toEpochMilli() - timestamp > CACHE_TTL_MS;
        }
    }
}
