package com.platform.speech.voice;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * CR-186: 음성 중계 손잡기에서 입장권을 꺼낸다(BIZ-253).
 *
 * <p>손잡기를 HTTP 오류로 거절하면 브라우저는 까닭 없이 1006 만 본다. 그래서 손잡기는 받아들이고 거절 까닭을
 * 속성에 남겨, 중계가 열리자마자 앱 번호(4401, 4403)로 닫는다.
 */
@Component
public class VoiceTicketHandshakeInterceptor implements HandshakeInterceptor {

    static final String ATTR_TICKET = "voice.ticket";
    static final String ATTR_REJECT = "voice.reject";

    static final int CLOSE_TICKET = 4401;
    static final int CLOSE_ORIGIN = 4403;

    private final VoiceTicketService ticketService;

    public VoiceTicketHandshakeInterceptor(VoiceTicketService ticketService) {
        this.ticketService = ticketService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String ticket = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("ticket");
        VoiceTicket t = ticketService.consume(ticket).orElse(null);
        if (t == null) {
            attributes.put(ATTR_REJECT, CLOSE_TICKET);
            return true;
        }
        String origin = request.getHeaders().getOrigin();
        if (t.origin() != null && !t.origin().equalsIgnoreCase(origin)) {
            attributes.put(ATTR_REJECT, CLOSE_ORIGIN);
            return true;
        }
        attributes.put(ATTR_TICKET, t);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
