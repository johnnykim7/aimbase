package com.platform.config;

import com.platform.domain.master.GlobalConfigEntity;
import com.platform.policy.AuditLogger;
import com.platform.repository.master.GlobalConfigRepository;
import com.platform.tenant.plan.SubscriptionPlanService;
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

    // CR-147: skills 는 스킬 묶음 상한과 스킬 도구 한도(T3-17 3.11). 여기 없으면 런타임 설정 화면에 안 나온다.
    // CR-191: plan 은 플랜 목록과 플랜별 분당 요청 한도(BIZ-153).
    private static final Set<String> CATEGORIES = Set.of("orchestrator", "session", "compaction", "widget", "llm", "skills", "plan");

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
     * 64비트 정수형 설정값 조회 (파일 크기·바이트 수용 등 int 범위를 넘을 수 있는 값 대상).
     */
    public long getLong(String key, long defaultValue) {
        String value = getString(key, null);
        if (value == null) return defaultValue;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid long for key '{}': '{}', using default {}", key, value, defaultValue);
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
     * CSV 리스트 설정값 조회. 공백은 trim, 빈 항목은 제외.
     */
    public List<String> getStringList(String key, List<String> defaultValue) {
        String value = getString(key, null);
        if (value == null || value.isBlank()) return defaultValue;
        List<String> result = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result.isEmpty() ? defaultValue : result;
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
        // CR-191: 플랜 목록, 기본 플랜, 한도는 서로 맞아야 해서 하나만 틀려도 같은 요청의 다른 값을 저장하지 않는다.
        // 그래서 저장 전에 요청 전체를 먼저 검사한다.
        Map<String, GlobalConfigEntity> targets = new HashMap<>();
        for (SettingUpdateRequest req : updates) {
            Optional<GlobalConfigEntity> opt = configRepository.findByConfigKey(req.key());
            if (opt.isEmpty()) {
                log.warn("Setting key not found: {}", req.key());
                continue;
            }
            validateValue(req.key(), req.value());
            targets.put(req.key(), opt.get());
        }
        validatePlanSettings(updates);

        int updated = 0;
        for (SettingUpdateRequest req : updates) {
            GlobalConfigEntity entity = targets.get(req.key());
            if (entity == null) {
                continue;
            }
            String oldValue = entity.getConfigValue();

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

    /**
     * BIZ-153: 플랜 목록에 든 이름마다 한도 키 행이 있어야 생성과 플랜 변경이 한도를 채울 수 있다.
     * 한도 키 행은 마이그레이션으로만 생기므로 목록에 새 이름을 적는 것만으로는 플랜을 더할 수 없다.
     */
    private void validatePlanSettings(List<SettingUpdateRequest> updates) {
        Map<String, String> changed = new HashMap<>();
        for (SettingUpdateRequest req : updates) {
            if (req.key().startsWith(SubscriptionPlanService.PREFIX)) {
                changed.put(req.key(), req.value());
            }
        }
        if (changed.isEmpty()) {
            return;
        }

        for (Map.Entry<String, String> e : changed.entrySet()) {
            if (SubscriptionPlanService.isRpmLimitKey(e.getKey())
                    && SubscriptionPlanService.parseRpmLimit(e.getValue()) == null) {
                throw new IllegalArgumentException(
                        "Plan rpm limit must be an integer of 0 or greater: " + e.getKey() + "=" + e.getValue());
            }
        }

        List<String> codes = SubscriptionPlanService.splitCodes(
                changed.containsKey(SubscriptionPlanService.CODES_KEY)
                        ? changed.get(SubscriptionPlanService.CODES_KEY)
                        : storedValue(SubscriptionPlanService.CODES_KEY));
        if (codes.isEmpty()) {
            throw new IllegalArgumentException(SubscriptionPlanService.CODES_KEY + " must not be empty");
        }
        for (String code : codes) {
            if (configRepository.findByConfigKey(SubscriptionPlanService.rpmLimitKey(code)).isEmpty()) {
                throw new IllegalArgumentException("No setting for plan " + code + ": "
                        + SubscriptionPlanService.rpmLimitKey(code));
            }
        }

        String defaultPlan = changed.containsKey(SubscriptionPlanService.DEFAULT_KEY)
                ? changed.get(SubscriptionPlanService.DEFAULT_KEY)
                : storedValue(SubscriptionPlanService.DEFAULT_KEY);
        if (defaultPlan == null || !codes.contains(defaultPlan.trim())) {
            throw new IllegalArgumentException(SubscriptionPlanService.DEFAULT_KEY + " must be one of: "
                    + String.join(", ", codes));
        }
    }

    private String storedValue(String key) {
        return configRepository.findByConfigKey(key).map(GlobalConfigEntity::getConfigValue).orElse(null);
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
