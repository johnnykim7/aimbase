package com.platform.mcp.agent;

import java.nio.charset.StandardCharsets;

/**
 * CR-074: TURN long-term credential 인증 자료 묶음.
 *
 * <p>{@link TurnTcpAllocator} 의 Allocate 두 번째 단계에서 만들어지고,
 * 이후 {@link TurnConnectionBindHandler} 가 ConnectionBind 송신 시 같은 자료를 재사용한다.
 *
 * <p>RFC 5389 / 5766 / 6062 — long-term credential 은 Allocate / Refresh / ConnectionBind
 * 모두에 동일한 username/realm/nonce/key 를 적용한다 (서버가 nonce 갱신 요구하기 전까지).
 */
public record TurnAuthMaterial(
        byte[] usernameBytes,
        byte[] realmBytes,
        byte[] nonceBytes,
        byte[] key
) {
    public static TurnAuthMaterial of(String username, String realm, String nonce, byte[] key) {
        return new TurnAuthMaterial(
                username.getBytes(StandardCharsets.UTF_8),
                realm.getBytes(StandardCharsets.UTF_8),
                nonce.getBytes(StandardCharsets.UTF_8),
                key
        );
    }
}
