package com.platform.workflow.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** CR-149 TC-WF-027: 실행 때 재시도 설정 해석. */
class ErrorHandlingTest {

    @Test
    void missing_settings_use_defaults() {
        assertThat(ErrorHandling.from(null)).isEqualTo(new ErrorHandling(2, 3000L));
        assertThat(ErrorHandling.from(Map.of())).isEqualTo(new ErrorHandling(2, 3000L));
    }

    @Test
    void missing_field_takes_its_default_not_zero() {
        assertThat(ErrorHandling.from(Map.of("retryMaxAttempts", 1))).isEqualTo(new ErrorHandling(1, 3000L));
        assertThat(ErrorHandling.from(Map.of("retryDelayMs", 0))).isEqualTo(new ErrorHandling(2, 0L));
    }

    @Test
    void legacy_fields_are_ignored_without_dropping_known_ones() {
        assertThat(ErrorHandling.from(Map.of("strategy", "stop_on_first", "retryMaxAttempts", 4)))
                .isEqualTo(new ErrorHandling(4, 3000L));
    }

    @Test
    void invalid_stored_values_fall_back_to_defaults() {
        assertThat(ErrorHandling.from(Map.of("retryMaxAttempts", -3, "retryDelayMs", "fast")))
                .isEqualTo(new ErrorHandling(2, 3000L));
    }
}
