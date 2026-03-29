package com.platform.llm.ratelimit;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Token Bucket 알고리즘 (CR-013).
 *
 * 프로바이더별 분당 요청 수(RPM)를 제어한다.
 * 버킷에 토큰이 있으면 즉시 통과, 없으면 대기 또는 거부.
 * 토큰은 시간 경과에 따라 자동 보충된다.
 */
public class TokenBucket {

    private final int maxTokens;        // 분당 최대 요청 수
    private final long refillIntervalMs; // 토큰 1개 보충 간격 (ms)
    private final AtomicLong availableTokens;
    private volatile long lastRefillTime;

    public TokenBucket(int requestsPerMinute) {
        this.maxTokens = requestsPerMinute;
        this.refillIntervalMs = 60_000L / requestsPerMinute;
        this.availableTokens = new AtomicLong(requestsPerMinute);
        this.lastRefillTime = System.currentTimeMillis();
    }

    /**
     * 토큰 1개를 소비한다. 성공하면 true, 버킷이 비었으면 false.
     */
    public synchronized boolean tryAcquire() {
        refill();
        if (availableTokens.get() > 0) {
            availableTokens.decrementAndGet();
            return true;
        }
        return false;
    }

    /**
     * 토큰이 보충될 때까지 대기 후 소비한다.
     * maxWaitMs 초과 시 false 반환.
     */
    public boolean tryAcquire(long maxWaitMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxWaitMs;
        while (System.currentTimeMillis() < deadline) {
            if (tryAcquire()) {
                return true;
            }
            Thread.sleep(Math.min(refillIntervalMs, deadline - System.currentTimeMillis()));
        }
        return false;
    }

    /** 다음 토큰까지 예상 대기 시간 (ms) */
    public long estimatedWaitMs() {
        refill();
        if (availableTokens.get() > 0) return 0;
        return refillIntervalMs - (System.currentTimeMillis() - lastRefillTime) % refillIntervalMs;
    }

    private void refill() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastRefillTime;
        long tokensToAdd = elapsed / refillIntervalMs;
        if (tokensToAdd > 0) {
            long newTokens = Math.min(maxTokens, availableTokens.get() + tokensToAdd);
            availableTokens.set(newTokens);
            lastRefillTime = now;
        }
    }

    public int getMaxTokens() { return maxTokens; }
    public long getAvailableTokens() { refill(); return availableTokens.get(); }
}
