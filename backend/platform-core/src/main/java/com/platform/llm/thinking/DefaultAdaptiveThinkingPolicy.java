package com.platform.llm.thinking;

import com.platform.config.PlatformSettingsService;
import org.springframework.stereotype.Component;

/**
 * CR-048 PRD-302: 기본 Adaptive Thinking 공식 구현.
 *
 * 공식:
 *   budget = base
 *     × (lastTurnToolCalls ≥ 3 ? tool_calls_multiplier : 1.0)
 *     × (lastTurnHadErrors   ? error_multiplier : 1.0)
 *     × (userQuestionLength > 500 ? long_question_multiplier : 1.0)
 *   cap = adaptive_thinking.cap (default 32000)
 *
 * 모든 계수는 PlatformSettings(CR-040)에서 런타임 조정 가능.
 */
@Component
public class DefaultAdaptiveThinkingPolicy implements AdaptiveThinkingPolicy {

    public static final String KEY_BASE = "adaptive_thinking.base_budget";
    public static final String KEY_TOOL_MUL = "adaptive_thinking.tool_calls_multiplier";
    public static final String KEY_ERR_MUL = "adaptive_thinking.error_multiplier";
    public static final String KEY_LONG_MUL = "adaptive_thinking.long_question_multiplier";
    public static final String KEY_CAP = "adaptive_thinking.cap";

    private static final int DEFAULT_BASE = 4000;
    private static final double DEFAULT_TOOL_MUL = 1.5;
    private static final double DEFAULT_ERR_MUL = 2.0;
    private static final double DEFAULT_LONG_MUL = 1.3;
    private static final int DEFAULT_CAP = 32000;

    private static final int TOOL_THRESHOLD = 3;
    private static final int LONG_QUESTION_THRESHOLD = 500;

    private final PlatformSettingsService settings;

    public DefaultAdaptiveThinkingPolicy(PlatformSettingsService settings) {
        this.settings = settings;
    }

    @Override
    public int calculate(AdaptiveThinkingInputs inputs) {
        if (inputs == null) inputs = AdaptiveThinkingInputs.empty();

        int base = settings.getInt(KEY_BASE, DEFAULT_BASE);
        double toolMul = getDouble(KEY_TOOL_MUL, DEFAULT_TOOL_MUL);
        double errMul = getDouble(KEY_ERR_MUL, DEFAULT_ERR_MUL);
        double longMul = getDouble(KEY_LONG_MUL, DEFAULT_LONG_MUL);
        int cap = settings.getInt(KEY_CAP, DEFAULT_CAP);

        double factor = 1.0;
        if (inputs.lastTurnToolCalls() >= TOOL_THRESHOLD) factor *= toolMul;
        if (inputs.lastTurnHadErrors()) factor *= errMul;
        if (inputs.userQuestionLength() > LONG_QUESTION_THRESHOLD) factor *= longMul;

        long budget = Math.round(base * factor);
        if (budget > cap) budget = cap;
        if (budget < base) budget = base;
        return (int) budget;
    }

    private double getDouble(String key, double defaultValue) {
        String raw = settings.getString(key, null);
        if (raw == null) return defaultValue;
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
