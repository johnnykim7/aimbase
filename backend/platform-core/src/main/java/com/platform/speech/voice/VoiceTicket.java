package com.platform.speech.voice;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * CR-186: 음성 대화 입장권 한 장의 내용(BIZ-253). Redis {@code voice:ticket:{ticket}} 에 둔다.
 *
 * @param origin 입장권을 받은 요청의 Origin. 웹소켓을 여는 화면도 같은 출처여야 한다. 없으면 null
 */
public record VoiceTicket(
        @JsonProperty("tenant_id") String tenantId,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("user_ref") String userRef,
        @JsonProperty("origin") String origin,
        @JsonProperty("connection_id") String connectionId) {
}
