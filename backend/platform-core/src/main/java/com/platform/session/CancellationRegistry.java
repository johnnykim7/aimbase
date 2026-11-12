package com.platform.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CR-046: 세션별 중지(abort) 토큰 레지스트리.
 *
 * SSE 스트림 진입 시 register, abort 호출 시 cancel, 종료 시 clear.
 * OrchestratorEngine.chatStream() / ToolCallHandler.executeLoopStream() 매 iteration이
 * isCancelled를 체크하여 break 한다.
 *
 * <p>CR-175: 표시만 걸면 어댑터가 답을 끝까지 받은 뒤에야 끝이 나는 경로(Claude CLI 실행기)가 있다.
 * 그런 스트림은 {@link #onCancel} 로 멈춤 동작을 걸어 두고, 사용자가 중지하면 그 동작이 한 번 돈다.
 */
@Component
public class CancellationRegistry {

    private static final Logger log = LoggerFactory.getLogger(CancellationRegistry.class);

    private final ConcurrentHashMap<String, AtomicBoolean> tokens = new ConcurrentHashMap<>();
    // 토큰은 요청마다 새로 만들어 같은 세션의 앞뒤 요청을 가른다. AtomicBoolean 은 동일성으로 비교된다.
    private final ConcurrentHashMap<AtomicBoolean, Runnable> hooks = new ConcurrentHashMap<>();

    public AtomicBoolean register(String sessionId) {
        AtomicBoolean token = new AtomicBoolean(false);
        AtomicBoolean prev = tokens.put(sessionId, token);
        if (prev != null) {
            // 같은 세션의 새 요청이 앞 요청을 끊는 경우다. 멈춤 동작은 돌리지 않는다. 비동기로 도는 멈춤이
            // 실행기에 늦게 닿으면 방금 시작한 새 답을 멈출 수 있다.
            prev.set(true);
            hooks.remove(prev);
        }
        return token;
    }

    /**
     * CR-175: 이 토큰이 사용자 중지로 끊길 때 한 번 돌 동작을 건다. 이미 끊겼으면 바로 돈다.
     * 동작은 중지 요청을 붙잡지 않도록 따로 돈다.
     */
    public void onCancel(AtomicBoolean token, Runnable hook) {
        if (token == null || hook == null) return;
        hooks.put(token, hook);
        if (token.get()) fire(token);
    }

    public boolean cancel(String sessionId) {
        AtomicBoolean token = tokens.get(sessionId);
        if (token == null) return false;
        token.set(true);
        fire(token);
        return true;
    }

    /**
     * CR-185: 세션이 아니라 그 요청의 토큰을 끊는다. 승인을 기다리다 스트림이 끊긴 요청만 멈추고,
     * 그사이 같은 세션에 들어온 새 요청은 건드리지 않는다.
     */
    public void cancel(AtomicBoolean token) {
        if (token == null) return;
        token.set(true);
        fire(token);
    }

    public boolean isCancelled(String sessionId) {
        AtomicBoolean token = tokens.get(sessionId);
        return token != null && token.get();
    }

    public AtomicBoolean getToken(String sessionId) {
        return tokens.get(sessionId);
    }

    public void clear(String sessionId) {
        AtomicBoolean token = tokens.remove(sessionId);
        if (token != null) hooks.remove(token);
    }

    /**
     * CR-175: 그 요청의 토큰일 때만 지운다. 앞 요청이 늦게 끝나며 같은 세션의 새 요청 토큰을 지우면
     * 새 요청의 중지가 먹지 않는다.
     */
    public void clear(String sessionId, AtomicBoolean token) {
        if (token == null) return;
        tokens.remove(sessionId, token);
        hooks.remove(token);
    }

    public boolean isActive(String sessionId) {
        return tokens.containsKey(sessionId);
    }

    private void fire(AtomicBoolean token) {
        Runnable hook = hooks.remove(token);
        if (hook == null) return;
        Thread.ofVirtual().name("cancel-hook").start(() -> {
            try {
                hook.run();
            } catch (RuntimeException e) {
                log.warn("cancel hook failed: {}", e.getMessage());
            }
        });
    }
}
