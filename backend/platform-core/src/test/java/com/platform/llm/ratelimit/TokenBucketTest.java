package com.platform.llm.ratelimit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBucketTest {

    @Test
    void initialBucket_hasFullTokens() {
        var bucket = new TokenBucket(10);
        assertThat(bucket.getAvailableTokens()).isEqualTo(10);
    }

    @Test
    void tryAcquire_consumesToken() {
        var bucket = new TokenBucket(5);
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.getAvailableTokens()).isEqualTo(4);
    }

    @Test
    void tryAcquire_failsWhenEmpty() {
        var bucket = new TokenBucket(2);
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isFalse();
    }

    @Test
    void tokensRefillOverTime() throws InterruptedException {
        var bucket = new TokenBucket(60); // 1 token per second
        // 전부 소비
        for (int i = 0; i < 60; i++) bucket.tryAcquire();
        assertThat(bucket.tryAcquire()).isFalse();

        // 1초 대기 → 토큰 보충
        Thread.sleep(1100);
        assertThat(bucket.tryAcquire()).isTrue();
    }

    @Test
    void tryAcquireWithWait_waitsAndSucceeds() throws InterruptedException {
        var bucket = new TokenBucket(60); // 1 token/sec
        for (int i = 0; i < 60; i++) bucket.tryAcquire();

        // 2초 대기 허용
        assertThat(bucket.tryAcquire(2000)).isTrue();
    }

    @Test
    void tryAcquireWithWait_timesOut() throws InterruptedException {
        var bucket = new TokenBucket(1); // 1 token/min
        bucket.tryAcquire(); // 소비

        // 100ms만 대기 → 타임아웃
        assertThat(bucket.tryAcquire(100)).isFalse();
    }
}
