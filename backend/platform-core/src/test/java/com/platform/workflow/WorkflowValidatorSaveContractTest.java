package com.platform.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CR-149 TC-WF-025, 026: 저장 때 실행 모드와 재시도 설정 검사. */
class WorkflowValidatorSaveContractTest {

    private final WorkflowValidator validator = new WorkflowValidator();

    @Test
    void graph_mode_defaults_to_dag_and_is_stored_lowercase() {
        assertThat(validator.graphMode(null)).isEqualTo("dag");
        assertThat(validator.graphMode(" ")).isEqualTo("dag");
        assertThat(validator.graphMode("Cyclic")).isEqualTo("cyclic");
    }

    @Test
    void unknown_graph_mode_is_rejected() {
        assertThatThrownBy(() -> validator.graphMode("loop"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("graphMode");
    }

    @Test
    void error_handling_accepts_known_non_negative_integers() {
        assertThatCode(() -> validator.validateErrorHandling(null)).doesNotThrowAnyException();
        assertThatCode(() -> validator.validateErrorHandling(Map.of())).doesNotThrowAnyException();
        assertThatCode(() -> validator.validateErrorHandling(Map.of("retryMaxAttempts", 0, "retryDelayMs", 5000L)))
                .doesNotThrowAnyException();
    }

    @Test
    void error_handling_rejects_legacy_or_unknown_fields() {
        assertThatThrownBy(() -> validator.validateErrorHandling(Map.of("strategy", "stop_on_first")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("errorHandling.strategy");
        assertThatThrownBy(() -> validator.validateErrorHandling(Map.of("maxRetries", 1)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("errorHandling.maxRetries");
    }

    @Test
    void error_handling_rejects_negative_fractional_or_missing_values() {
        Map<String, Object> nullValue = new HashMap<>();
        nullValue.put("retryDelayMs", null);
        for (Map<String, Object> bad : new Map[]{
                Map.of("retryMaxAttempts", -1),
                Map.of("retryMaxAttempts", 1.5),
                Map.of("retryDelayMs", "3000"),
                Map.of("retryMaxAttempts", 3_000_000_000L),
                nullValue}) {
            assertThatThrownBy(() -> validator.validateErrorHandling(bad))
                    .as(bad.toString())
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("non-negative integer");
        }
    }
}
