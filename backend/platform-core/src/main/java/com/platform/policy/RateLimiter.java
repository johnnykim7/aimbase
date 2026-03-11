package com.platform.policy;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 슬라이딩 윈도우 방식의 인메모리 속도 제한(Rate Limiter).
 *
 * 각 키(세션ID + 인텐트 등)에 대해 지정된 시간 창(windowMs) 내
 * maxRequests 초과 요청을 차단.
 *
 * Thread-safe: ConcurrentHashMap + synchronized per-key 큐 조작.
 */
@Component
public class RateLimiter {

    // key → 요청 타임스탬프 큐 (오래된 것부터 제거)
    private final ConcurrentHashMap<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    /**
     * 요청 허용 여부 확인. 허용 시 카운터를 증가시키고 true 반환.
     *
     * @param key         속도 제한 키 (예: "sessionId:intent")
     * @param maxRequests 시간 창 내 허용 최대 요청 수
     * @param windowMs    슬라이딩 윈도우 크기 (밀리초)
     * @return 허용이면 true, 초과이면 false
     */
    public boolean isAllowed(String key, int maxRequests, long windowMs) {
        long now = System.currentTimeMillis();
        long windowStart = now - windowMs;

        Deque<Long> queue = windows.computeIfAbsent(key, k -> new ArrayDeque<>());

        synchronized (queue) {
            // 만료된 타임스탬프 제거
            while (!queue.isEmpty() && queue.peekFirst() < windowStart) {
                queue.pollFirst();
            }

            if (queue.size() < maxRequests) {
                queue.addLast(now);
                return true;
            }
            return false;
        }
    }

    /**
     * 특정 키의 현재 요청 수 조회 (테스트/모니터링용).
     *
     * @param key      속도 제한 키
     * @param windowMs 슬라이딩 윈도우 크기 (밀리초)
     * @return 시간 창 내 현재 요청 수
     */
    public int getCurrentCount(String key, long windowMs) {
        long windowStart = System.currentTimeMillis() - windowMs;
        Deque<Long> queue = windows.get(key);
        if (queue == null) return 0;

        synchronized (queue) {
            return (int) queue.stream().filter(ts -> ts >= windowStart).count();
        }
    }
}
