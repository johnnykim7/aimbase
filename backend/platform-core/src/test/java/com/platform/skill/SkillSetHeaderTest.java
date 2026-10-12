package com.platform.skill;

import com.platform.tool.SkillRef;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CR-147 러너가 서버 MCP 호출에 싣는 스킬 지정 머리글. TC-SKB-024.
 */
class SkillSetHeaderTest {

    private static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void encoded_value_decodes_to_the_same_pins() {
        List<SkillRef> pins = List.of(new SkillRef("ax-proposal", "tenant", 1), new SkillRef("pdf", "platform", 3));

        String value = SkillSetHeader.encode(pins);

        assertThat(value).doesNotContain("=", "+", "/");
        assertThat(SkillSetHeader.decode(value)).isEqualTo(pins);
    }

    @Test
    void scope_is_normalized() {
        assertThat(SkillSetHeader.decode(b64("[{\"name\":\"a\",\"scope\":\"TENANT\",\"version\":2}]")))
                .containsExactly(new SkillRef("a", "tenant", 2));
    }

    @Test
    void unreadable_value_is_rejected() {
        assertThatThrownBy(() -> SkillSetHeader.decode("not base64 !"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BIZ-123");
        assertThatThrownBy(() -> SkillSetHeader.decode(b64("{\"name\":\"a\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BIZ-123");
    }

    @Test
    void item_without_a_usable_pin_is_rejected() {
        for (String json : List.of(
                "[{\"scope\":\"tenant\",\"version\":1}]",
                "[{\"name\":\" \",\"scope\":\"tenant\",\"version\":1}]",
                "[{\"name\":\"a\",\"scope\":\"global\",\"version\":1}]",
                "[{\"name\":\"a\",\"scope\":\"tenant\",\"version\":0}]",
                "[{\"name\":\"a\",\"scope\":\"tenant\",\"version\":\"1\"}]",
                "[\"a\"]")) {
            assertThatThrownBy(() -> SkillSetHeader.decode(b64(json)))
                    .as(json)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("항목이 올바르지 않습니다");
        }
    }
}
