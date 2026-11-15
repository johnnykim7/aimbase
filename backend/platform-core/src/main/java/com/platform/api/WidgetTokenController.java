package com.platform.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.platform.auth.ApiKeyAuthenticationFilter;
import com.platform.auth.JwtProvider;
import com.platform.config.PlatformSettingsService;
import com.platform.session.ChatKeys;
import com.platform.session.ChatRejectedException;
import com.platform.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * CR-058: Chat Widget SDK 단기 토큰 발급.
 *
 * 소비앱 BFF가 API Key로 호출하여 브라우저에 전달할 단기 JWT를 발급받는다.
 * JWT로는 발급 불가 — 브라우저가 자신을 위해 직접 발급받는 구조를 차단한다.
 */
@RestController
@RequestMapping("/api/v1/sessions")
public class WidgetTokenController {

    private static final Logger log = LoggerFactory.getLogger(WidgetTokenController.class);

    private final JwtProvider jwtProvider;
    private final PlatformSettingsService settings;

    public WidgetTokenController(JwtProvider jwtProvider, PlatformSettingsService settings) {
        this.jwtProvider = jwtProvider;
        this.settings = settings;
    }

    @PostMapping("/issue-widget-token")
    public ResponseEntity<?> issueWidgetToken(@RequestBody IssueRequest req,
                                              HttpServletRequest httpReq) {
        // 1) API Key 인증 통과분만 허용 (JWT 로 호출 금지)
        if (ApiKeyAuthenticationFilter.resolveApiKey(httpReq) == null) {
            return ResponseEntity.status(401)
                    .body(ApiResponse.error("API Key is required to issue widget token"));
        }

        String tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return ResponseEntity.status(401)
                    .body(ApiResponse.error("Tenant context is not set"));
        }

        // 2) Origin 화이트리스트 검증
        List<String> allowedOrigins = settings.getStringList("widget.allowed-origins", List.of());
        if (req.origin == null || req.origin.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("origin is required"));
        }
        if (!allowedOrigins.contains(req.origin)) {
            log.warn("Widget token denied — origin '{}' not in whitelist (tenant={})", req.origin, tenantId);
            return ResponseEntity.status(400)
                    .body(ApiResponse.error("origin is not allowed for widget embedding"));
        }

        // 3) Scope 화이트리스트 교집합
        List<String> allowedScopes = settings.getStringList(
                "widget.allowed-scopes",
                List.of("chat:stream", "chat:upload", "chat:stt", "chat:tts", "chat:voice", "workflow:subscribe", "rag:read"));
        List<String> requestedScopes = (req.scopes != null && !req.scopes.isEmpty())
                ? req.scopes : allowedScopes;
        List<String> grantedScopes = new ArrayList<>();
        for (String s : requestedScopes) {
            if (allowedScopes.contains(s)) grantedScopes.add(s);
        }
        if (grantedScopes.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("no scopes granted — check widget.allowed-scopes"));
        }

        // 4) TTL cap
        long defaultTtl = settings.getInt("widget.token-ttl-seconds", 1800);
        long maxTtl = settings.getInt("widget.token-max-ttl-seconds", 3600);
        long ttl = req.ttlSeconds != null ? req.ttlSeconds : defaultTtl;
        if (ttl > maxTtl) {
            log.info("Widget token TTL capped from {} to {} (tenant={})", ttl, maxTtl, tenantId);
            ttl = maxTtl;
        }
        if (ttl < 60) ttl = 60; // 최소 1분

        // 5) CR-194: 묻는 사람 이름과 함께 보기 키(PRD-637)
        String userRef = ChatKeys.blankToNull(req.userRef);
        String userName = ChatKeys.blankToNull(req.userName);
        String shareKey = ChatKeys.blankToNull(req.shareKey);
        if (userName != null && userName.length() > ChatKeys.USER_NAME_MAX) {
            throw new ChatRejectedException(HttpStatus.BAD_REQUEST, "widget_token_invalid:user_name");
        }
        if (shareKey != null && !ChatKeys.isValid(shareKey)) {
            throw new ChatRejectedException(HttpStatus.BAD_REQUEST, "widget_token_invalid:share_key");
        }
        // 함께 보는 대화에 묻는 사람을 남기려면 그 사람이 누군지 알아야 한다
        if (shareKey != null && userRef == null) {
            throw new ChatRejectedException(HttpStatus.BAD_REQUEST, "widget_token_invalid:user_ref");
        }

        // 6) 발급
        String token = jwtProvider.generateWidgetToken(
                tenantId, req.projectId, req.userRef, userName, shareKey, grantedScopes, req.origin, ttl);
        Instant expiresAt = Instant.now().plusSeconds(ttl);
        long refreshAfter = Math.max(60, ttl - 300); // exp - 5min

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", token);
        body.put("expires_at", expiresAt.toString());
        body.put("refresh_after", refreshAfter);
        body.put("scopes", grantedScopes);
        return ResponseEntity.ok(ApiResponse.ok(body));
    }

    /**
     * 모르는 칸은 무시한다. 붙이는 앱이 aimbase 보다 먼저 칸을 더해 보내도 토큰 발급이 막히지 않게 하려는 것이다
     * (CR-194 PRD-637).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IssueRequest {
        @JsonProperty("project_id")
        public String projectId;
        @JsonProperty("user_ref")
        public String userRef;
        @JsonProperty("session_hint")
        public String sessionHint;
        @JsonProperty("ttl_seconds")
        public Long ttlSeconds;
        public String origin;
        public List<String> scopes;
        @JsonProperty("user_name")
        public String userName;
        @JsonProperty("share_key")
        public String shareKey;
    }
}
