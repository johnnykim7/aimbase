package com.platform.mcp.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

/**
 * CR-076: TURN long-term credential 의 mutable holder.
 *
 * <p>nonce 는 RFC 5389 §10.2.1.2 에 따라 서버가 임의 시점에 stale 처리 (438 Stale Nonce) 후
 * 새 nonce 를 응답에 실어 보낼 수 있다. {@link TurnAuthMaterial} 은 record(immutable) 이라
 * stale 시 갱신할 수 없어, 본 세션 객체가 항상 최신 자료를 보관한다.
 *
 * <p>호출자({@link TurnConnectionBindHandler} / {@link AgentLifecycle}) 는 매 송신 시
 * {@link #current()} 로 최신 {@link TurnAuthMaterial} 을 가져와 사용하고,
 * 438 응답을 만나면 {@link #updateNonce(String)} 로 새 nonce 를 등록한다.
 *
 * <p>RFC 5389 — long-term credential key = MD5(username:realm:password) 는 nonce 와 무관.
 * 따라서 nonce 만 바뀌면 key 그대로 재사용 가능 (Allocate 단계에서 한 번 계산해 보관).
 */
public final class TurnAuthSession {

    private static final Logger log = LoggerFactory.getLogger(TurnAuthSession.class);

    private final String username;
    private final String realm;
    private final AtomicReference<TurnAuthMaterial> current = new AtomicReference<>();

    private TurnAuthSession(String username, String realm, TurnAuthMaterial initial) {
        this.username = username;
        this.realm = realm;
        this.current.set(initial);
    }

    public static TurnAuthSession of(String username, String realm, String nonce, byte[] key) {
        return new TurnAuthSession(username, realm, TurnAuthMaterial.of(username, realm, nonce, key));
    }

    /** 매 송신 직전 호출. */
    public TurnAuthMaterial current() {
        return current.get();
    }

    /**
     * 서버가 새 nonce 를 줄 때 호출. key 는 (username, realm, password) 함수이므로 nonce 와 무관.
     */
    public void updateNonce(String newNonce) {
        if (newNonce == null || newNonce.isBlank()) return;
        TurnAuthMaterial existing = current.get();
        TurnAuthMaterial updated = TurnAuthMaterial.of(username, realm, newNonce, existing.key());
        if (current.compareAndSet(existing, updated)) {
            log.debug("TURN nonce updated (len={})", newNonce.length());
        }
    }

    /** 디버그/테스트 편의용. */
    String username() { return username; }
    String realm() { return realm; }
}
