package com.platform.skill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.tool.SkillRef;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * CR-147: CLI 러너가 서버 MCP 호출에 실어 보내는 스킬 지정 머리글(T3-17 3.8, BIZ-123).
 * 값은 [{name, scope, version}] JSON 을 base64url(패딩 없음)로 감싼 것이다.
 * 러너가 만들고 서버 필터가 풀어 ToolContext.skillSet 으로 넘긴다.
 */
public final class SkillSetHeader {

    public static final String NAME = "X-Aimbase-Skill-Set";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SkillSetHeader() {}

    public static String encode(List<SkillRef> skills) {
        List<Map<String, Object>> items = skills.stream()
                .map(r -> Map.<String, Object>of("name", r.name(), "scope", r.scope(), "version", r.version()))
                .toList();
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(MAPPER.writeValueAsBytes(items));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("스킬 지정 머리글을 만들지 못했습니다: " + e.getMessage(), e);
        }
    }

    /**
     * 머리글 값을 푼다. 값이 깨졌으면 IllegalArgumentException 이다.
     * 단계가 고정한 판 대신 최신 판으로 조용히 넘어가지 않게 하려는 것이다.
     */
    public static List<SkillRef> decode(String value) {
        List<?> items;
        try {
            items = MAPPER.readValue(Base64.getUrlDecoder().decode(value.trim().getBytes(StandardCharsets.US_ASCII)),
                    List.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("BIZ-123: " + NAME + " 머리글을 읽을 수 없습니다");
        }
        List<SkillRef> skills = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> m)
                    || !(m.get("name") instanceof String name) || name.isBlank()
                    || !(m.get("scope") instanceof String scope) || scopeOf(scope) == null
                    || !(m.get("version") instanceof Integer version) || version < 1) {
                throw new IllegalArgumentException("BIZ-123: " + NAME + " 머리글 항목이 올바르지 않습니다: " + item);
            }
            skills.add(new SkillRef(name, scopeOf(scope).value(), version));
        }
        return List.copyOf(skills);
    }

    private static SkillScope scopeOf(String value) {
        try {
            return SkillScope.fromValue(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
