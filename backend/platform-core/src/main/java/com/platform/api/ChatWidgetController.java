package com.platform.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.platform.auth.UserPrincipal;
import com.platform.session.ChatRejectedException;
import com.platform.session.ChatWidgetSettingsService;
import com.platform.session.FollowUpService;
import com.platform.session.MessageFeedbackService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * CR-175: 채팅 위젯이 대화 말고 부르는 것들. 위젯 설정, 답 평가, 후속 질문(PRD-370~372).
 */
@RestController
@RequestMapping("/api/v1/chat")
public class ChatWidgetController {

    /** JwtAuthenticationFilter 가 위젯 토큰에 붙이는 역할. */
    private static final String WIDGET_ROLE = "WIDGET";

    private final ChatWidgetSettingsService widgetSettings;
    private final MessageFeedbackService feedbackService;
    private final FollowUpService followUpService;

    public ChatWidgetController(ChatWidgetSettingsService widgetSettings,
                                MessageFeedbackService feedbackService,
                                FollowUpService followUpService) {
        this.widgetSettings = widgetSettings;
        this.feedbackService = feedbackService;
        this.followUpService = followUpService;
    }

    @GetMapping("/widget-settings")
    @PreAuthorize("hasAuthority('SCOPE_chat:stream') or isAuthenticated()")
    public ApiResponse<Map<String, Object>> widgetSettings(
            @RequestParam(name = "connection_id", required = false) String connectionId,
            @RequestParam(name = "locale", required = false) String locale) {
        return ApiResponse.ok(widgetSettings.settings(connectionId, locale));
    }

    @PutMapping("/feedback")
    @PreAuthorize("hasAuthority('SCOPE_chat:stream') or isAuthenticated()")
    public ApiResponse<Map<String, Object>> saveFeedback(@AuthenticationPrincipal UserPrincipal principal,
                                                         @RequestBody FeedbackRequest body) {
        var saved = feedbackService.save(requester(principal), body.sessionId(), body.turnId(),
                body.rating(), body.reasonCodes(), body.comment());
        return ApiResponse.ok(feedbackService.toResponse(saved));
    }

    @DeleteMapping("/feedback")
    @PreAuthorize("hasAuthority('SCOPE_chat:stream') or isAuthenticated()")
    public ApiResponse<Map<String, Object>> deleteFeedback(@AuthenticationPrincipal UserPrincipal principal,
                                                           @RequestParam(name = "turn_id") String turnId) {
        int deleted = feedbackService.delete(requester(principal), turnId);
        return ApiResponse.ok(Map.of("turn_id", turnId, "deleted", deleted > 0));
    }

    /** 관리 화면 평가 목록. 위젯 토큰으로는 남의 평가를 볼 수 없게 막는다. */
    @GetMapping("/feedback")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<Map<String, Object>>> listFeedback(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) String rating,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String model,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (principal == null || WIDGET_ROLE.equals(principal.getRole())) {
            throw new ChatRejectedException(HttpStatus.FORBIDDEN, "feedback_list_forbidden");
        }
        return ApiResponse.page(feedbackService.list(rating, from, to, model, page, size));
    }

    @PostMapping("/sessions/{sessionId}/follow-ups")
    @PreAuthorize("hasAuthority('SCOPE_chat:stream') or isAuthenticated()")
    public ApiResponse<Map<String, Object>> followUps(@AuthenticationPrincipal UserPrincipal principal,
                                                      @PathVariable String sessionId,
                                                      @RequestBody FollowUpRequest body) {
        return ApiResponse.ok(followUpService.generate(requester(principal), sessionId,
                body.turnId(), body.connectionId()));
    }

    /** 위젯 토큰은 user_ref(없으면 subject), 로그인은 사용자 id. 세션 주인 대조와 평가 주인에 쓴다. */
    private static String requester(UserPrincipal principal) {
        if (principal == null || principal.getId() == null) {
            throw new ChatRejectedException(HttpStatus.UNAUTHORIZED, "unauthenticated");
        }
        return principal.getId();
    }

    public record FeedbackRequest(
            @JsonProperty("session_id") String sessionId,
            @JsonProperty("turn_id") String turnId,
            @JsonProperty("rating") String rating,
            @JsonProperty("reason_codes") List<String> reasonCodes,
            @JsonProperty("comment") String comment) {}

    public record FollowUpRequest(
            @JsonProperty("turn_id") String turnId,
            @JsonProperty("connection_id") String connectionId) {}
}
