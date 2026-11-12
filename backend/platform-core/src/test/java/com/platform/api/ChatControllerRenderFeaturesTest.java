package com.platform.api;

import com.platform.session.ChatRejectedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CR-183 TC-CHT-099 대화 요청 render_features 검사(BIZ-223). */
class ChatControllerRenderFeaturesTest {

    @Test
    @DisplayName("겹친 값은 한 번만 남기고 순서를 지킨다, 비면 null")
    void dedupAndEmpty() {
        assertThat(ChatController.parseRenderFeatures(List.of("chart", "math", "chart")))
                .containsExactly("chart", "math");
        assertThat(ChatController.parseRenderFeatures(null)).isNull();
        assertThat(ChatController.parseRenderFeatures(List.of())).isNull();
    }

    @Test
    @DisplayName("대문자, 빈 값, 11개는 400 render_features_invalid")
    void invalid() {
        assertRejected(List.of("Chart"));
        assertRejected(List.of(""));
        assertRejected(Collections.nCopies(11, "chart"));
    }

    private static void assertRejected(List<String> raw) {
        assertThatThrownBy(() -> ChatController.parseRenderFeatures(raw))
                .isInstanceOfSatisfying(ChatRejectedException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).isEqualTo("render_features_invalid");
                });
    }
}
