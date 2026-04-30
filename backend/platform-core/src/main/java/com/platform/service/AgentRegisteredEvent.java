package com.platform.service;

/**
 * CR-081: agent 가 새로 등록(또는 재등록)되었음을 알리는 도메인 이벤트.
 *
 * <p>publish 자: {@link AgentRegistryService#register} — register() 가 신규/갱신 모두에서 발행한다.
 * 재할당(CR-079) 으로 인한 재등록도 동일 경로로 진입하므로 이 이벤트로 통일된다.
 *
 * <p>listener: 예) FallbackChainExecutor — 같은 사용자/agent 의 회복 신호로 받아 CB reset.
 *
 * <p>비동기로 처리되어도 안전하도록 모든 필드는 immutable.
 *
 * @param agentId      AgentRegistry.id (UUID 문자열)
 * @param userId       위젯 토큰 user_ref (없을 수 있음)
 * @param tenantId     테넌트 식별자 (이벤트 발행 시점의 TenantContext)
 * @param wasReregister true 면 같은 publicAddress+mcpPort 또는 같은 userId 의 갱신 등록 — CB reset 같은
 *                      회복-신호성 동작은 이 플래그가 true 일 때 의미가 있다.
 */
public record AgentRegisteredEvent(
        String agentId,
        String userId,
        String tenantId,
        boolean wasReregister
) {
}
