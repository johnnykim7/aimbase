package com.platform.api;

import com.platform.domain.ConnectionEntity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CR-197: 연결 조회 응답에 HTTP 연결의 인증키(config.auth.value)를 싣지 않는다.
 * 저장된 엔티티를 고치면 영속 상태가 바뀌므로 응답용 사본만 가린다.
 * value_env 는 환경변수 이름이라 비밀이 아니다.
 */
final class ConnectionSecretMasker {

    static final String MASK = "***";

    private ConnectionSecretMasker() {
    }

    static ConnectionEntity masked(ConnectionEntity source) {
        if (source == null || !hasAuthValue(source.getConfig())) {
            return source;
        }
        ConnectionEntity copy = new ConnectionEntity();
        copy.setId(source.getId());
        copy.setName(source.getName());
        copy.setAdapter(source.getAdapter());
        copy.setType(source.getType());
        copy.setStatus(source.getStatus());
        copy.setHealthConfig(source.getHealthConfig());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        copy.setConfig(withAuthValue(source.getConfig(), MASK));
        return copy;
    }

    /**
     * 조회 응답을 고쳐 다시 보낸 수정 요청에는 가린 값이 그대로 들어 있다.
     * 그 값으로 저장하면 키가 사라지므로 저장된 값을 지킨다.
     */
    static Map<String, Object> keepStoredAuthValue(Map<String, Object> incoming, Map<String, Object> stored) {
        if (!MASK.equals(authValue(incoming))) {
            return incoming;
        }
        Object storedValue = authValue(stored);
        if (storedValue == null) {
            return incoming;
        }
        return withAuthValue(incoming, storedValue);
    }

    private static boolean hasAuthValue(Map<String, Object> config) {
        return authValue(config) instanceof String s && !s.isEmpty();
    }

    private static Object authValue(Map<String, Object> config) {
        if (config == null || !(config.get("auth") instanceof Map<?, ?> auth)) {
            return null;
        }
        return auth.get("value");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> withAuthValue(Map<String, Object> config, Object value) {
        Map<String, Object> auth = new LinkedHashMap<>((Map<String, Object>) config.get("auth"));
        auth.put("value", value);
        Map<String, Object> copy = new LinkedHashMap<>(config);
        copy.put("auth", auth);
        return copy;
    }
}
