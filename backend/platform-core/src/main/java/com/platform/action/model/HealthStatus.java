package com.platform.action.model;

public record HealthStatus(boolean ok, long latencyMs) {

    public static HealthStatus healthy(long latencyMs) {
        return new HealthStatus(true, latencyMs);
    }

    public static HealthStatus unhealthy() {
        return new HealthStatus(false, -1);
    }
}
