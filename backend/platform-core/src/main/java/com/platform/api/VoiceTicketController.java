package com.platform.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.platform.auth.WidgetScopes;
import com.platform.session.ChatRejectedException;
import com.platform.speech.voice.VoiceTicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * CR-186: 음성 대화 입장권 {@code POST /api/v1/chat/voice/tickets}(PRD-582). 오류는 {@code ApiResponse.error(code)} 이다.
 */
@RestController
@RequestMapping("/api/v1/chat/voice")
@Tag(name = "Chat Voice", description = "CR-186 위젯 음성 대화")
public class VoiceTicketController {

    static final String SCOPE = "chat:voice";

    private final VoiceTicketService ticketService;

    public VoiceTicketController(VoiceTicketService ticketService) {
        this.ticketService = ticketService;
    }

    public record TicketRequest(@JsonProperty("session_id") String sessionId) {}

    @PostMapping("/tickets")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "음성 대화 입장권. 실시간 받아쓰기 중계 웹소켓을 한 번 열 수 있다")
    public ApiResponse<Map<String, Object>> issue(@RequestBody TicketRequest request,
                                                  Authentication auth,
                                                  HttpServletRequest http) {
        if (WidgetScopes.lacks(auth, SCOPE)) {
            throw new ChatRejectedException(HttpStatus.FORBIDDEN, "voice_forbidden");
        }
        return ApiResponse.ok(ticketService.issue(
                request.sessionId(), WidgetScopes.principalId(auth), http.getHeader(HttpHeaders.ORIGIN)));
    }
}
