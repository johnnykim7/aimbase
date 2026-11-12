package com.platform.api;

import com.platform.image.ImageGenerationService;
import com.platform.repository.ConversationSessionRepository;
import com.platform.session.ChatRejectedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/** CR-184 PRD-542: 위젯이 만든 그림을 대화 주인에게만 준다(BIZ-233). */
@RestController
@RequestMapping("/api/v1/chat/images")
@Tag(name = "Chat", description = "LLM 채팅 API")
public class ChatImageController {

    /** 그림은 만든 뒤 바뀌지 않아 하루 동안 브라우저가 다시 받지 않게 한다. 대화 주인만 받으므로 공용 캐시는 막는다. */
    private static final Duration CACHE_MAX_AGE = Duration.ofDays(1);
    private static final String CODE_FORBIDDEN = "forbidden";

    private final ImageGenerationService imageGeneration;
    private final ConversationSessionRepository sessionRepository;

    public ChatImageController(ImageGenerationService imageGeneration,
                               ConversationSessionRepository sessionRepository) {
        this.imageGeneration = imageGeneration;
        this.sessionRepository = sessionRepository;
    }

    @GetMapping("/{imageId}")
    @PreAuthorize("hasAuthority('SCOPE_chat:stream') or isAuthenticated()")
    @Operation(summary = "만든 그림 받기", description = "그 대화에서 만든 그림 바이트. 대화 주인만 받는다.")
    public ResponseEntity<byte[]> image(@PathVariable String imageId,
                                        @RequestParam("session_id") String sessionId) {
        ImageGenerationService.LoadedImage loaded = imageGeneration.loadForSession(imageId, sessionId);
        String owner = sessionRepository.findBySessionId(sessionId).map(s -> s.getUserId()).orElse(null);
        String current = currentUserId();
        if (owner != null && !owner.isBlank() && current != null && !owner.equals(current)) {
            throw new ChatRejectedException(HttpStatus.FORBIDDEN, CODE_FORBIDDEN);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(loaded.image().getMediaType()))
                .cacheControl(CacheControl.maxAge(CACHE_MAX_AGE).cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().build().toString())
                .body(loaded.bytes());
    }

    /** 대화 지우기(ConversationController)와 같은 사람 판정. */
    private static String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        if (auth.getPrincipal() instanceof com.platform.auth.UserPrincipal up) return up.getUsername();
        return auth.getName();
    }
}
