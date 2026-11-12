package com.platform.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.platform.auth.WidgetScopes;
import com.platform.session.ChatRejectedException;
import com.platform.speech.TtsServiceRouter;
import com.platform.speech.WidgetTtsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CR-186: 위젯 답 읽어 주기 {@code POST /api/v1/chat/tts}(PRD-580). 오류는 {@code ApiResponse.error(code)} 이다.
 */
@RestController
@RequestMapping("/api/v1/chat")
@Tag(name = "Chat TTS", description = "CR-186 위젯 답 읽어 주기")
public class WidgetTtsController {

    static final String SCOPE = "chat:tts";

    private final WidgetTtsService ttsService;

    public WidgetTtsController(WidgetTtsService ttsService) {
        this.ttsService = ttsService;
    }

    public record TtsRequest(@JsonProperty("session_id") String sessionId,
                             @JsonProperty("text") String text) {}

    @PostMapping("/tts")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "위젯 답 읽어 주기. 읽을 글 한 조각을 MP3 로 돌려준다")
    public ResponseEntity<byte[]> tts(@RequestBody TtsRequest request, Authentication auth) {
        if (WidgetScopes.lacks(auth, SCOPE)) {
            throw new ChatRejectedException(HttpStatus.FORBIDDEN, "tts_forbidden");
        }
        TtsServiceRouter.Result r = ttsService.synthesize(
                request.sessionId(), request.text(), WidgetScopes.principalId(auth));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .header(SpeechController.ENGINE_HEADER, r.engine())
                .body(r.body());
    }
}
