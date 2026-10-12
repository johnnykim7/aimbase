package com.platform.skill;

import java.util.Locale;

/**
 * CR-147: 스킬 묶음 범위. 이름이 겹치면 TENANT 가 먼저다(BIZ-120).
 */
public enum SkillScope {
    TENANT,
    PLATFORM;

    public String value() { return name().toLowerCase(Locale.ROOT); }

    public static SkillScope fromValue(String value) {
        if (value == null || value.isBlank()) return null;
        return SkillScope.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
