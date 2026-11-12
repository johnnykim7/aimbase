package com.platform.speech.voice;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CR-186: 지금 열려 있는 음성 중계(BIZ-253). 맥 받아쓰기 서버가 동시에 감당할 수 있는 수가 적어 전체 상한을 두고,
 * 한 세션은 한 번에 하나만 연다.
 *
 * <p>api 는 한 대로 돈다. 여러 대가 되면 이 표를 Redis 로 옮긴다.
 */
@Component
public class VoiceRelayRegistry {

    public enum Open { OK, SESSION_BUSY, FULL }

    private final Set<String> open = ConcurrentHashMap.newKeySet();

    public synchronized Open tryOpen(String key, int max) {
        if (open.contains(key)) return Open.SESSION_BUSY;
        if (open.size() >= max) return Open.FULL;
        open.add(key);
        return Open.OK;
    }

    public void close(String key) {
        open.remove(key);
    }

    public boolean isOpen(String key) {
        return open.contains(key);
    }

    public int openCount() {
        return open.size();
    }

    public static String key(String tenantId, String sessionId) {
        return tenantId + ":" + sessionId;
    }
}
