package com.platform.llm;

import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * CR-165: 연결 config 의 API 키를 읽는다.
 *
 * <p>키 값을 테넌트 DB 에 두지 않으려면 서버 환경변수에 값을 두고 config 에는 그 변수 이름만
 * {@code apiKey_env} 로 둔다. 연결 조회 응답과 DB 에 키가 실리지 않는다.
 * 값이 든 {@code apiKey} 가 먼저라 값으로 등록된 기존 연결은 그대로 돈다.
 */
public final class ConnectionApiKeys {

    public static final String API_KEY = "apiKey";
    public static final String API_KEY_ENV = "apiKey_env";

    private ConnectionApiKeys() {}

    public static String resolve(Map<String, Object> config) {
        return resolve(config, System::getenv);
    }

    static String resolve(Map<String, Object> config, UnaryOperator<String> env) {
        if (config == null) return null;
        String plain = nonBlank(config.get(API_KEY));
        if (plain != null) return plain;
        String envName = nonBlank(config.get(API_KEY_ENV));
        return envName != null ? nonBlank(env.apply(envName)) : null;
    }

    private static String nonBlank(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }
}
