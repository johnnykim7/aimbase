package com.platform.service;

/**
 * CR-071 Phase 3: ClaudeCliAdapter 가 ClaudeCliRunner 호출에 필요로 하는 endpoint 정보.
 *
 * @param agentId          AgentRegistry id (UUID toString)
 * @param runnerEndpoint   Runner HTTP base URL (예: http://host:8290)
 * @param runnerApiKeyHash Runner X-Api-Key 의 SHA-256 hex (실 키 평문 저장 금지)
 */
public record AgentEndpoint(
        String agentId,
        String runnerEndpoint,
        String runnerApiKeyHash
) {}
