package com.platform.skill;

import java.util.List;
import java.util.Map;

/**
 * CR-147: 묶음 올리기 결과. created 가 false 면 직전 판과 내용이 같아 새 판을 만들지 않았다(BIZ-119).
 */
public record UploadOutcome(
        String name,
        SkillScope scope,
        int version,
        boolean created,
        boolean reactivated,
        List<Map<String, Object>> warnings
) {}
