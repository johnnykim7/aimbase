package com.platform.mcp.agent;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-076: TurnAuthSession 의 nonce 갱신 동작 검증.
 */
class TurnAuthSessionTest {

    @Test
    void of_buildsInitialMaterial() {
        TurnAuthSession session = TurnAuthSession.of("u", "r", "nonce-initial", new byte[16]);
        TurnAuthMaterial m = session.current();
        assertThat(new String(m.usernameBytes(), StandardCharsets.UTF_8)).isEqualTo("u");
        assertThat(new String(m.realmBytes(), StandardCharsets.UTF_8)).isEqualTo("r");
        assertThat(new String(m.nonceBytes(), StandardCharsets.UTF_8)).isEqualTo("nonce-initial");
    }

    @Test
    void updateNonce_replacesNonceKeepingKey() {
        byte[] originalKey = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
        TurnAuthSession session = TurnAuthSession.of("u", "r", "nonce-A", originalKey);

        TurnAuthMaterial before = session.current();
        session.updateNonce("nonce-B");
        TurnAuthMaterial after = session.current();

        assertThat(before).isNotSameAs(after);
        assertThat(new String(after.nonceBytes(), StandardCharsets.UTF_8)).isEqualTo("nonce-B");
        // key 는 그대로 (long-term credential — nonce 와 무관)
        assertThat(after.key()).isEqualTo(originalKey);
        // username/realm 도 그대로
        assertThat(new String(after.usernameBytes(), StandardCharsets.UTF_8)).isEqualTo("u");
        assertThat(new String(after.realmBytes(), StandardCharsets.UTF_8)).isEqualTo("r");
    }

    @Test
    void updateNonce_ignoresNullOrBlank() {
        TurnAuthSession session = TurnAuthSession.of("u", "r", "nonce-A", new byte[16]);
        TurnAuthMaterial before = session.current();
        session.updateNonce(null);
        session.updateNonce("");
        session.updateNonce("   ");
        assertThat(session.current()).isSameAs(before);
    }
}
