package com.platform.orchestrator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GenericCircuitBreakerTest {

    private GenericCircuitBreaker cb;

    @BeforeEach
    void setUp() {
        cb = new GenericCircuitBreaker("test", 3, 100); // 100ms open duration for fast test
    }

    @Test
    void initialState_isClosed() {
        assertThat(cb.getState()).isEqualTo(GenericCircuitBreaker.State.CLOSED);
        assertThat(cb.allowRequest()).isTrue();
    }

    @Test
    void afterThresholdFailures_transitionsToOpen() {
        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.getState()).isEqualTo(GenericCircuitBreaker.State.CLOSED);

        cb.recordFailure(); // 3rd → OPEN
        assertThat(cb.getState()).isEqualTo(GenericCircuitBreaker.State.OPEN);
        assertThat(cb.allowRequest()).isFalse();
    }

    @Test
    void open_transitionsToHalfOpenAfterDuration() throws InterruptedException {
        cb.recordFailure();
        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.getState()).isEqualTo(GenericCircuitBreaker.State.OPEN);

        Thread.sleep(150); // openDuration=100ms 경과
        assertThat(cb.allowRequest()).isTrue();
        assertThat(cb.getState()).isEqualTo(GenericCircuitBreaker.State.HALF_OPEN);
    }

    @Test
    void halfOpen_successReturnsToClosed() throws InterruptedException {
        cb.recordFailure();
        cb.recordFailure();
        cb.recordFailure();
        Thread.sleep(150);
        cb.allowRequest(); // → HALF_OPEN

        cb.recordSuccess();
        assertThat(cb.getState()).isEqualTo(GenericCircuitBreaker.State.CLOSED);
        assertThat(cb.getConsecutiveFailures()).isZero();
    }

    @Test
    void halfOpen_failureReturnsToOpen() throws InterruptedException {
        cb.recordFailure();
        cb.recordFailure();
        cb.recordFailure();
        Thread.sleep(150);
        cb.allowRequest(); // → HALF_OPEN

        cb.recordFailure(); // HALF_OPEN에서 실패 → OPEN
        assertThat(cb.getState()).isEqualTo(GenericCircuitBreaker.State.OPEN);
    }

    @Test
    void successResetsFailureCount() {
        cb.recordFailure();
        cb.recordFailure();
        cb.recordSuccess();
        assertThat(cb.getConsecutiveFailures()).isZero();
        assertThat(cb.getState()).isEqualTo(GenericCircuitBreaker.State.CLOSED);
    }

    @Test
    void manualReset() {
        cb.recordFailure();
        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.getState()).isEqualTo(GenericCircuitBreaker.State.OPEN);

        cb.reset();
        assertThat(cb.getState()).isEqualTo(GenericCircuitBreaker.State.CLOSED);
        assertThat(cb.getConsecutiveFailures()).isZero();
    }
}
