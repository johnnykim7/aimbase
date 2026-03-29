package com.platform.orchestrator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 범용 서킷 브레이커 (PRD-123).
 *
 * 상태 전이: CLOSED → (연속 실패 >= threshold) → OPEN → (시간 경과) → HALF_OPEN → 성공 → CLOSED / 실패 → OPEN
 *
 * failureThreshold, openDurationMs를 생성자로 받아 용도별 설정 가능.
 * 스레드 세이프 (AtomicReference).
 */
public class GenericCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(GenericCircuitBreaker.class);

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final String name;
    private final int failureThreshold;
    private final long openDurationMs;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile Instant lastFailureAt;
    private volatile Instant lastSuccessAt;
    private volatile Instant openUntil;

    public GenericCircuitBreaker(String name, int failureThreshold, long openDurationMs) {
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.openDurationMs = openDurationMs;
    }

    /** 요청 허용 여부. OPEN 상태면 시간 경과 후 HALF_OPEN 전환. */
    public boolean allowRequest() {
        State current = state.get();
        if (current == State.CLOSED) return true;
        if (current == State.OPEN && Instant.now().isAfter(openUntil)) {
            if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                log.info("[{}] HALF_OPEN 전환: openDuration 경과", name);
            }
            return true;
        }
        return current == State.HALF_OPEN;
    }

    /** 성공 → CLOSED 복귀 */
    public void recordSuccess() {
        consecutiveFailures.set(0);
        lastSuccessAt = Instant.now();
        State prev = state.getAndSet(State.CLOSED);
        if (prev != State.CLOSED) {
            log.info("[{}] CLOSED 복귀: 성공 기록", name);
        }
    }

    /** 실패 → 임계치 초과 시 OPEN 전환 */
    public void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        lastFailureAt = Instant.now();

        if (state.get() == State.HALF_OPEN) {
            transitionToOpen(failures);
        } else if (failures >= failureThreshold) {
            transitionToOpen(failures);
        }
    }

    /** 수동 리셋 */
    public void reset() {
        state.set(State.CLOSED);
        consecutiveFailures.set(0);
        openUntil = null;
        log.info("[{}] 수동 리셋: CLOSED", name);
    }

    private void transitionToOpen(int failures) {
        state.set(State.OPEN);
        openUntil = Instant.now().plusMillis(openDurationMs);
        log.warn("[{}] OPEN: consecutiveFailures={}, openUntil={}", name, failures, openUntil);
    }

    // ── 상태 조회 ──
    public String getName() { return name; }
    public State getState() { return state.get(); }
    public int getConsecutiveFailures() { return consecutiveFailures.get(); }
    public Instant getLastFailureAt() { return lastFailureAt; }
    public Instant getLastSuccessAt() { return lastSuccessAt; }
    public Instant getOpenUntil() { return openUntil; }
}
