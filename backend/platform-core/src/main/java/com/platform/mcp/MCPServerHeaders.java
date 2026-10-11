package com.platform.mcp;

import java.net.http.HttpRequest;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * MCP 서버 등록 config 의 headers 칸 (CR-145, BIZ-117).
 *
 * <p>값은 상대 서버의 열쇠 같은 비밀이라 응답에는 {@link #MASK} 로 내보낸다.
 * 조회한 config 를 그대로 수정 요청으로 되돌려 보내도 열쇠가 지워지지 않도록
 * 수정 요청의 {@link #MASK} 는 저장된 값으로 되돌린다.
 */
public final class MCPServerHeaders {

    public static final String CONFIG_KEY = "headers";
    public static final String MASK = "****";

    private MCPServerHeaders() {}

    /**
     * config 의 headers 를 검증해 이름과 값으로 꺼낸다. 없으면 빈 Map.
     * 예외 메시지에는 값을 넣지 않는다.
     */
    public static Map<String, String> from(Map<String, Object> config) {
        Object raw = config == null ? null : config.get(CONFIG_KEY);
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("config.headers must be an object");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        // 이름과 값의 허용 여부는 실제로 요청을 만드는 HttpClient 판정을 그대로 쓴다(제한 머리글 목록을 따로 두지 않는다).
        HttpRequest.Builder probe = HttpRequest.newBuilder();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String name = String.valueOf(entry.getKey());
            if (!(entry.getValue() instanceof String value) || value.isBlank()) {
                throw new IllegalArgumentException("config.headers['" + name + "'] must be a non-empty string");
            }
            if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("config.headers['" + name + "'] is duplicated");
            }
            try {
                probe.setHeader(name, value);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("config.headers['" + name + "'] is not an allowed HTTP header");
            }
            headers.put(name, value);
        }
        return headers;
    }

    /** 응답용 사본. headers 값만 {@link #MASK} 로 바꾸고 원본 config 는 건드리지 않는다. */
    public static Map<String, Object> masked(Map<String, Object> config) {
        if (config == null || !config.containsKey(CONFIG_KEY)) {
            return config;
        }
        Map<String, Object> copy = new LinkedHashMap<>(config);
        if (config.get(CONFIG_KEY) instanceof Map<?, ?> headers) {
            Map<String, Object> maskedHeaders = new LinkedHashMap<>();
            headers.keySet().forEach(name -> maskedHeaders.put(String.valueOf(name), MASK));
            copy.put(CONFIG_KEY, maskedHeaders);
        } else {
            copy.put(CONFIG_KEY, MASK);
        }
        return copy;
    }

    /**
     * 수정 요청 config 의 {@link #MASK} 값을 저장된 값으로 되돌린 사본.
     * 저장된 값이 없는 이름에 {@link #MASK} 를 보내면 거부한다.
     */
    public static Map<String, Object> restoreMasked(Map<String, Object> stored, Map<String, Object> incoming) {
        if (incoming == null || !(incoming.get(CONFIG_KEY) instanceof Map<?, ?> headers)) {
            return incoming;
        }
        Map<String, Object> storedHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (stored != null && stored.get(CONFIG_KEY) instanceof Map<?, ?> storedMap) {
            storedMap.forEach((name, value) -> storedHeaders.put(String.valueOf(name), value));
        }
        Map<String, Object> restored = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : headers.entrySet()) {
            String name = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (MASK.equals(value)) {
                value = storedHeaders.get(name);
                if (value == null) {
                    throw new IllegalArgumentException("config.headers['" + name + "'] is masked but has no stored value");
                }
            }
            restored.put(name, value);
        }
        Map<String, Object> copy = new LinkedHashMap<>(incoming);
        copy.put(CONFIG_KEY, restored);
        return copy;
    }
}
