package com.platform.llm.adapter;

import com.anthropic.models.messages.CacheControlEphemeral;

/**
 * CR-047 PRD-300: Anthropic prompt cache의 cache_control TTL을 소스별로 결정한다.
 *
 * - System prompt: 1h TTL (long-lived, 시스템 프롬프트가 자주 바뀌지 않음)
 * - Tool schemas: 1h TTL (자주 바뀌지 않으며, Anthropic API 처리 순서 'tools→system→messages' 상
 *                 짧은 TTL이 긴 TTL보다 앞에 올 수 없으므로 system 과 동일 TTL 로 통일)
 * - Recent messages: cache 미적용 (null 반환 → cache_control 누락)
 *
 * 각 구현체는 정적 인스턴스로 사용된다.
 */
public interface CacheControlStrategy {

    /**
     * 해당 소스에 적용할 CacheControlEphemeral을 반환.
     * null 반환 시 cache_control을 적용하지 않는다.
     */
    CacheControlEphemeral resolve();

    /** System prompt: 1h TTL. */
    CacheControlStrategy SYSTEM_PROMPT = () -> CacheControlEphemeral.builder()
            .ttl(CacheControlEphemeral.Ttl.TTL_1H)
            .build();

    /**
     * Tool schemas: 1h TTL (system 과 동일).
     *
     * <p>이전엔 5m 였으나 Anthropic API 의 cache_control 처리 순서 규칙
     * ("tools → system → messages 순으로 처리되며 짧은 TTL 블록이 긴 TTL 블록 뒤에 올 수 없음")에
     * 따라 system(1h) 앞에 오는 tools 가 5m 면 400 에러가 발생한다. tools 도 1h 로 통일.
     */
    CacheControlStrategy TOOL_SCHEMA = () -> CacheControlEphemeral.builder()
            .ttl(CacheControlEphemeral.Ttl.TTL_1H)
            .build();

    /** Messages: cache 미적용. */
    CacheControlStrategy MESSAGE_NONE = () -> null;
}
