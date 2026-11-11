package com.platform.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** CR-175 TC-CHT-021: 사용자 중지에 걸어 둔 멈춤 동작. */
class CancellationRegistryTest {

    private final CancellationRegistry registry = new CancellationRegistry();

    @Test
    @DisplayName("중지하면 걸어 둔 동작이 한 번만 돈다")
    void cancelRunsHookOnce() throws InterruptedException {
        AtomicBoolean token = registry.register("s1");
        CountDownLatch ran = new CountDownLatch(1);
        AtomicInteger count = new AtomicInteger();
        registry.onCancel(token, () -> { count.incrementAndGet(); ran.countDown(); });

        assertThat(registry.cancel("s1")).isTrue();
        assertThat(registry.cancel("s1")).isTrue();

        assertThat(ran.await(2, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(100);
        assertThat(count.get()).isEqualTo(1);
        assertThat(token.get()).isTrue();
    }

    @Test
    @DisplayName("동작을 걸기 전에 이미 중지됐으면 걸 때 바로 돈다")
    void hookRegisteredAfterCancelRunsImmediately() throws InterruptedException {
        AtomicBoolean token = registry.register("s1");
        registry.cancel("s1");
        CountDownLatch ran = new CountDownLatch(1);

        registry.onCancel(token, ran::countDown);

        assertThat(ran.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("같은 세션의 새 요청이 앞 요청을 끊을 때는 앞 요청의 동작을 돌리지 않는다")
    void nextRequestDoesNotRunPreviousHook() throws InterruptedException {
        AtomicBoolean first = registry.register("s1");
        AtomicInteger count = new AtomicInteger();
        registry.onCancel(first, count::incrementAndGet);

        AtomicBoolean second = registry.register("s1");
        Thread.sleep(100);

        assertThat(first.get()).isTrue();
        assertThat(second.get()).isFalse();
        assertThat(count.get()).isZero();
    }

    @Test
    @DisplayName("앞 요청이 늦게 끝나도 새 요청의 토큰과 동작은 남는다")
    void lateClearKeepsNewerToken() throws InterruptedException {
        AtomicBoolean first = registry.register("s1");
        AtomicBoolean second = registry.register("s1");
        CountDownLatch ran = new CountDownLatch(1);
        registry.onCancel(second, ran::countDown);

        registry.clear("s1", first);

        assertThat(registry.isActive("s1")).isTrue();
        assertThat(registry.cancel("s1")).isTrue();
        assertThat(ran.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("끝난 요청은 동작을 지워 뒤늦은 중지에 돌지 않는다")
    void clearedRequestDoesNotRunHook() throws InterruptedException {
        AtomicBoolean token = registry.register("s1");
        AtomicInteger count = new AtomicInteger();
        registry.onCancel(token, count::incrementAndGet);

        registry.clear("s1", token);
        assertThat(registry.cancel("s1")).isFalse();
        Thread.sleep(100);

        assertThat(count.get()).isZero();
    }
}
