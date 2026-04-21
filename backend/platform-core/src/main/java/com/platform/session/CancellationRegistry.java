package com.platform.session;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CR-046: 세션별 중지(abort) 토큰 레지스트리.
 *
 * SSE 스트림 진입 시 register, abort 호출 시 cancel, 종료 시 clear.
 * OrchestratorEngine.chatStream() / ToolCallHandler.executeLoopStream() 매 iteration이
 * isCancelled를 체크하여 break 한다.
 */
@Component
public class CancellationRegistry {

    private final ConcurrentHashMap<String, AtomicBoolean> tokens = new ConcurrentHashMap<>();

    public AtomicBoolean register(String sessionId) {
        AtomicBoolean token = new AtomicBoolean(false);
        AtomicBoolean prev = tokens.put(sessionId, token);
        if (prev != null) {
            prev.set(true);
        }
        return token;
    }

    public boolean cancel(String sessionId) {
        AtomicBoolean token = tokens.get(sessionId);
        if (token == null) return false;
        token.set(true);
        return true;
    }

    public boolean isCancelled(String sessionId) {
        AtomicBoolean token = tokens.get(sessionId);
        return token != null && token.get();
    }

    public AtomicBoolean getToken(String sessionId) {
        return tokens.get(sessionId);
    }

    public void clear(String sessionId) {
        tokens.remove(sessionId);
    }

    public boolean isActive(String sessionId) {
        return tokens.containsKey(sessionId);
    }
}
