package com.platform.tool.builtin;

import com.platform.orchestrator.GenericCircuitBreaker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * ClaudeCodeTool 전용 서킷 브레이커.
 * GenericCircuitBreaker에 위임 (PRD-123 리팩토링).
 */
@Component
@ConditionalOnProperty(name = "claude-code.enabled", havingValue = "true", matchIfMissing = false)
public class ClaudeCodeCircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final GenericCircuitBreaker delegate;

    public ClaudeCodeCircuitBreaker() {
        this.delegate = new GenericCircuitBreaker("ClaudeCode", 3, 5 * 60 * 1000L);
    }

    public boolean allowRequest() { return delegate.allowRequest(); }
    public void recordSuccess() { delegate.recordSuccess(); }
    public void recordFailure() { delegate.recordFailure(); }
    public void reset() { delegate.reset(); }

    public State getState() {
        return State.valueOf(delegate.getState().name());
    }
    public int getConsecutiveFailures() { return delegate.getConsecutiveFailures(); }
    public Instant getLastFailureAt() { return delegate.getLastFailureAt(); }
    public Instant getLastSuccessAt() { return delegate.getLastSuccessAt(); }
    public Instant getOpenUntil() { return delegate.getOpenUntil(); }
}
