package com.platform.config;

import com.platform.speech.voice.VoiceRelayHandler;
import com.platform.speech.voice.VoiceTicketHandshakeInterceptor;
import com.platform.speech.voice.VoiceTicketService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * CR-186: 위젯 음성 대화 중계 웹소켓(BIZ-253). 출처는 여기서 막지 않고 입장권에 적힌 출처와 대조한다
 * ({@link VoiceTicketHandshakeInterceptor}). 소비앱마다 출처가 달라 여기에 목록을 둘 수 없기 때문이다.
 */
@Configuration
@EnableWebSocket
public class VoiceWebSocketConfig implements WebSocketConfigurer {

    private final VoiceRelayHandler relayHandler;
    private final VoiceTicketHandshakeInterceptor ticketInterceptor;

    public VoiceWebSocketConfig(VoiceRelayHandler relayHandler, VoiceTicketHandshakeInterceptor ticketInterceptor) {
        this.relayHandler = relayHandler;
        this.ticketInterceptor = ticketInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(relayHandler, VoiceTicketService.STREAM_PATH)
                .addInterceptors(ticketInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
