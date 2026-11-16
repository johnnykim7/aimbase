package com.platform.clitoken;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * CR-196 PRD-646, PRD-647: 실행기 계정 폴더 하나의 1년 토큰 상태 한 줄. 시각은 Asia/Seoul 로 적는다.
 * 테넌트 API 는 tenant_id, tenant_name, checked_at 을 비워 보낸다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TokenRow(
        @JsonProperty("tenant_id") String tenantId,
        @JsonProperty("tenant_name") String tenantName,
        @JsonProperty("agent_name") String agentName,
        @JsonProperty("config_dir") String configDir,
        @JsonProperty("managed_by") String managedBy,
        @JsonProperty("present") boolean present,
        @JsonProperty("issued_at") String issuedAt,
        @JsonProperty("expires_at") String expiresAt,
        @JsonProperty("days_left") Long daysLeft,
        @JsonProperty("reachable") boolean reachable,
        @JsonProperty("connection_ids") List<String> connectionIds,
        @JsonProperty("checked_at") String checkedAt) {
}
