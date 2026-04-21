package com.platform.llm.adapter;

import com.anthropic.models.messages.CacheControlEphemeral;

/**
 * CR-047 PRD-300: Anthropic prompt cache의 cache_control TTL을 소스별로 결정한다.
 *
 * - System prompt: 1h TTL (long-lived, 시스템 프롬프트가 자주 바뀌지 않음)
 * - Tool schemas: 5m TTL (도구 추가/제거 시 자연스러운 invalidation)
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

    /** Tool schemas: 5m TTL. */
    CacheControlStrategy TOOL_SCHEMA = () -> CacheControlEphemeral.builder()
            .ttl(CacheControlEphemeral.Ttl.TTL_5M)
            .build();

    /** Messages: cache 미적용. */
    CacheControlStrategy MESSAGE_NONE = () -> null;
}
