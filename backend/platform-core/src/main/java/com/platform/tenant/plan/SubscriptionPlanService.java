package com.platform.tenant.plan;

import com.platform.config.PlatformSettingsService;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * CR-191 BIZ-153: 플랜 목록과 플랜별 분당 요청 한도를 운영 설정(global_config plan.*)에서 읽는다.
 * 플랜 이름과 값은 운영에서 바꾸므로 코드에 두지 않는다. 테넌트 생성과 플랜 변경이 여기서 처음 한도를 받는다.
 * CR-205 BIZ-298: 채팅 분당 요청 한도(plan.<플랜>.chat-rpm-limit)도 같은 규칙으로 읽는다.
 */
@Service
public class SubscriptionPlanService {

    public static final String PREFIX = "plan.";
    public static final String CODES_KEY = "plan.codes";
    public static final String DEFAULT_KEY = "plan.default";
    private static final String RPM_LIMIT_SUFFIX = ".api-rpm-limit";
    private static final String CHAT_RPM_LIMIT_SUFFIX = ".chat-rpm-limit";

    private final PlatformSettingsService settings;

    public SubscriptionPlanService(PlatformSettingsService settings) {
        this.settings = settings;
    }

    public static String rpmLimitKey(String plan) {
        return PREFIX + plan + RPM_LIMIT_SUFFIX;
    }

    public static String chatRpmLimitKey(String plan) {
        return PREFIX + plan + CHAT_RPM_LIMIT_SUFFIX;
    }

    /** 플랜별 한도 키 둘(api, chat)을 가린다. 둘 다 0 이상의 정수여야 한다(BIZ-153, BIZ-298). */
    public static boolean isRpmLimitKey(String key) {
        return key.startsWith(PREFIX) && (key.endsWith(RPM_LIMIT_SUFFIX) || key.endsWith(CHAT_RPM_LIMIT_SUFFIX));
    }

    /** 0 은 무제한이라 받는다(BIZ-142). 0 이상의 정수가 아니면 null. */
    public static Integer parseRpmLimit(String raw) {
        if (raw == null) return null;
        try {
            int value = Integer.parseInt(raw.trim());
            return value >= 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static List<String> splitCodes(String raw) {
        if (raw == null) return List.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public List<String> codes() {
        return splitCodes(settings.getString(CODES_KEY, null));
    }

    public String defaultPlan() {
        String value = settings.getString(DEFAULT_KEY, null);
        return value == null ? null : value.trim();
    }

    public String requireKnown(String plan) {
        List<String> codes = codes();
        if (plan == null || !codes.contains(plan)) {
            throw new IllegalArgumentException("plan must be one of: " + String.join(", ", codes));
        }
        return plan;
    }

    /** 플랜을 고르지 않고 만든 테넌트는 기본 플랜으로 시작한다. */
    public String resolveForCreate(String requested) {
        return requireKnown(requested == null || requested.isBlank() ? defaultPlan() : requested);
    }

    public int rpmLimit(String plan) {
        return requireLimit(rpmLimitKey(plan));
    }

    public int chatRpmLimit(String plan) {
        return requireLimit(chatRpmLimitKey(plan));
    }

    private int requireLimit(String key) {
        Integer value = parseRpmLimit(settings.getString(key, null));
        if (value == null) {
            throw new IllegalStateException("Plan setting is missing or invalid: " + key);
        }
        return value;
    }

    /**
     * 구독 행이 없는 테넌트의 한도. 설정 행이 아직 없는 서버(마이그레이션 전)에서는 받은 값을 쓴다.
     */
    public int defaultPlanRpmLimit(int fallback) {
        return defaultPlanLimit(SubscriptionPlanService::rpmLimitKey, fallback);
    }

    public int defaultPlanChatRpmLimit(int fallback) {
        return defaultPlanLimit(SubscriptionPlanService::chatRpmLimitKey, fallback);
    }

    private int defaultPlanLimit(java.util.function.Function<String, String> keyOf, int fallback) {
        String plan = defaultPlan();
        if (plan == null || plan.isEmpty()) return fallback;
        Integer value = parseRpmLimit(settings.getString(keyOf.apply(plan), null));
        return value != null ? value : fallback;
    }

    public PlanCatalog catalog() {
        List<PlanInfo> plans = codes().stream()
                .map(code -> new PlanInfo(code, rpmLimit(code), chatRpmLimit(code)))
                .toList();
        return new PlanCatalog(defaultPlan(), plans);
    }

    public record PlanInfo(String code, int apiRpmLimit, int chatRpmLimit) {}

    public record PlanCatalog(String defaultPlan, List<PlanInfo> plans) {}
}
