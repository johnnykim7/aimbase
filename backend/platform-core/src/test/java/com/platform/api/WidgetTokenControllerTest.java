package com.platform.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.auth.JwtProvider;
import com.platform.config.PlatformSettingsService;
import com.platform.repository.KnowledgeSourceRepository;
import com.platform.session.ChatRejectedException;
import com.platform.session.RagSourceAccess;
import io.jsonwebtoken.Claims;
import com.platform.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * CR-058: WidgetTokenController 발급 정책 검증.
 */
@ExtendWith(MockitoExtension.class)
class WidgetTokenControllerTest {

    @Mock private PlatformSettingsService settings;
    @Mock private HttpServletRequest request;
    @Mock private KnowledgeSourceRepository sources;

    private JwtProvider jwtProvider;
    private WidgetTokenController controller;

    @BeforeEach
    void setUp() {
        jwtProvider = new JwtProvider(
                "cr058-widget-controller-test-secret-which-is-long-enough-1234",
                1800_000L, 604800_000L);
        controller = new WidgetTokenController(jwtProvider, settings, new RagSourceAccess(sources));
        TenantContext.setTenantId("tenant-abc");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void returnsToken_whenOriginAndScopesAllowed() {
        when(request.getHeader("X-API-Key")).thenReturn("test-api-key");
        when(settings.getStringList("widget.allowed-origins", List.of()))
                .thenReturn(List.of("https://oms.com"));
        when(settings.getStringList(eq("widget.allowed-scopes"), org.mockito.ArgumentMatchers.<List<String>>any()))
                .thenReturn(List.of("chat:stream", "rag:read"));
        when(settings.getInt("widget.token-ttl-seconds", 1800)).thenReturn(1800);
        when(settings.getInt("widget.token-max-ttl-seconds", 3600)).thenReturn(3600);

        WidgetTokenController.IssueRequest req = new WidgetTokenController.IssueRequest();
        req.origin = "https://oms.com";
        req.scopes = List.of("chat:stream");
        req.ttlSeconds = 600L;

        ResponseEntity<?> response = controller.issueWidgetToken(req, request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Object rawBody = Objects.requireNonNull(response.getBody(), "body");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) ((ApiResponse<Map<String, Object>>) rawBody).data();
        assertThat(body.get("token")).isInstanceOf(String.class);
        @SuppressWarnings("unchecked")
        List<String> grantedScopes = (List<String>) body.get("scopes");
        assertThat(grantedScopes).containsExactly("chat:stream");
    }

    @Test
    void rejects_whenApiKeyHeaderMissing() {
        when(request.getHeader("X-API-Key")).thenReturn(null);

        WidgetTokenController.IssueRequest req = new WidgetTokenController.IssueRequest();
        req.origin = "https://oms.com";

        ResponseEntity<?> response = controller.issueWidgetToken(req, request);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @DisplayName("IT-01-010 6 Authorization_ApiKey_로_온_요청도_발급한다")
    void returnsToken_whenApiKeyInAuthorizationHeader() {
        when(request.getHeader("X-API-Key")).thenReturn(null);
        when(request.getHeader("Authorization")).thenReturn("ApiKey test-api-key");
        when(settings.getStringList("widget.allowed-origins", List.of()))
                .thenReturn(List.of("https://oms.com"));
        when(settings.getStringList(eq("widget.allowed-scopes"), org.mockito.ArgumentMatchers.<List<String>>any()))
                .thenReturn(List.of("chat:stream"));
        when(settings.getInt("widget.token-ttl-seconds", 1800)).thenReturn(1800);
        when(settings.getInt("widget.token-max-ttl-seconds", 3600)).thenReturn(3600);

        WidgetTokenController.IssueRequest req = new WidgetTokenController.IssueRequest();
        req.origin = "https://oms.com";
        req.scopes = List.of("chat:stream");

        ResponseEntity<?> response = controller.issueWidgetToken(req, request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void rejects_whenOriginBlank() {
        when(request.getHeader("X-API-Key")).thenReturn("k");

        WidgetTokenController.IssueRequest req = new WidgetTokenController.IssueRequest();
        req.origin = "";

        ResponseEntity<?> response = controller.issueWidgetToken(req, request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void rejects_whenOriginNotWhitelisted() {
        when(request.getHeader("X-API-Key")).thenReturn("k");
        when(settings.getStringList("widget.allowed-origins", List.of()))
                .thenReturn(List.of("https://other.com"));

        WidgetTokenController.IssueRequest req = new WidgetTokenController.IssueRequest();
        req.origin = "https://oms.com";

        ResponseEntity<?> response = controller.issueWidgetToken(req, request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void rejects_whenRequestedScopesAllDenied() {
        when(request.getHeader("X-API-Key")).thenReturn("k");
        when(settings.getStringList("widget.allowed-origins", List.of()))
                .thenReturn(List.of("https://oms.com"));
        when(settings.getStringList(eq("widget.allowed-scopes"), org.mockito.ArgumentMatchers.<List<String>>any()))
                .thenReturn(List.of("chat:stream"));

        WidgetTokenController.IssueRequest req = new WidgetTokenController.IssueRequest();
        req.origin = "https://oms.com";
        req.scopes = List.of("admin:all");

        ResponseEntity<?> response = controller.issueWidgetToken(req, request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void capsTtlToMaximum() {
        when(request.getHeader("X-API-Key")).thenReturn("k");
        when(settings.getStringList("widget.allowed-origins", List.of()))
                .thenReturn(List.of("https://oms.com"));
        when(settings.getStringList(eq("widget.allowed-scopes"), org.mockito.ArgumentMatchers.<List<String>>any()))
                .thenReturn(List.of("chat:stream"));
        when(settings.getInt("widget.token-ttl-seconds", 1800)).thenReturn(1800);
        when(settings.getInt("widget.token-max-ttl-seconds", 3600)).thenReturn(3600);

        WidgetTokenController.IssueRequest req = new WidgetTokenController.IssueRequest();
        req.origin = "https://oms.com";
        req.ttlSeconds = 999999L; // 초과 요청

        ResponseEntity<?> response = controller.issueWidgetToken(req, request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Object rawBody = Objects.requireNonNull(response.getBody(), "body");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) ((ApiResponse<Map<String, Object>>) rawBody).data();
        long refreshAfter = ((Number) body.get("refresh_after")).longValue();
        // TTL 캡 3600 → refresh_after = 3600 - 300 = 3300
        assertThat(refreshAfter).isEqualTo(3300L);
    }

    @Test
    void rejects_whenTenantContextMissing() {
        TenantContext.clear();
        when(request.getHeader("X-API-Key")).thenReturn("k");

        WidgetTokenController.IssueRequest req = new WidgetTokenController.IssueRequest();
        req.origin = "https://oms.com";

        ResponseEntity<?> response = controller.issueWidgetToken(req, request);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    /**
     * CR-058: 요청 바디의 snake_case 키가 IssueRequest 필드로 올바르게 매핑되는지 확인.
     * 실행 중 발견된 버그 — Spring 기본 Jackson 이 camelCase 만 매핑하던 문제 회귀 방지용.
     */
    @Test
    void issueRequest_bindsSnakeCaseFromJson() throws Exception {
        String json = """
            {
              "project_id": "rescue",
              "user_ref": "user_123",
              "session_hint": "order_detail",
              "ttl_seconds": 600,
              "origin": "https://oms.com",
              "scopes": ["chat:stream"]
            }
            """;
        WidgetTokenController.IssueRequest bound =
                new ObjectMapper().readValue(json, WidgetTokenController.IssueRequest.class);

        assertThat(bound.projectId).isEqualTo("rescue");
        assertThat(bound.userRef).isEqualTo("user_123");
        assertThat(bound.sessionHint).isEqualTo("order_detail");
        assertThat(bound.ttlSeconds).isEqualTo(600L);
        assertThat(bound.origin).isEqualTo("https://oms.com");
        assertThat(bound.scopes).containsExactly("chat:stream");
    }

    private static <T> T eq(T value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }

    @Test
    @DisplayName("TC-CHT-159 1, 2 허용 자료 두 개가 빈 값과 중복을 뺀 채 토큰 claim 에 실린다")
    void issues_token_with_rag_source_ids() {
        stubAllowed();
        when(sources.existsById("kb_a")).thenReturn(true);
        when(sources.existsById("kb_b")).thenReturn(true);
        WidgetTokenController.IssueRequest req = new WidgetTokenController.IssueRequest();
        req.origin = "https://oms.com";
        req.ragSourceIds = List.of("kb_a", " ", "kb_b", "kb_a");

        Claims claims = jwtProvider.extractClaims(issue(req));

        assertThat(claims.get("rag_source_ids", List.class)).containsExactly("kb_a", "kb_b");
    }

    @Test
    @DisplayName("TC-CHT-159 3 테넌트에 없는 자료가 하나라도 있으면 400 widget_token_invalid:rag_source_id")
    void rejects_unknown_rag_source_id() {
        stubAllowed();
        when(sources.existsById("kb_a")).thenReturn(true);
        WidgetTokenController.IssueRequest req = new WidgetTokenController.IssueRequest();
        req.origin = "https://oms.com";
        req.ragSourceIds = List.of("kb_a", "kb_x");

        assertThatThrownBy(() -> controller.issueWidgetToken(req, request))
                .isInstanceOf(ChatRejectedException.class).hasMessage("widget_token_invalid:rag_source_id")
                .extracting("status").isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("TC-CHT-159 6 칸이 없으면 claim 도 없다")
    void no_claim_without_rag_source_ids() {
        stubAllowed();
        WidgetTokenController.IssueRequest req = new WidgetTokenController.IssueRequest();
        req.origin = "https://oms.com";

        Claims claims = jwtProvider.extractClaims(issue(req));

        assertThat(claims.containsKey("rag_source_ids")).isFalse();
    }

    private void stubAllowed() {
        when(request.getHeader("X-API-Key")).thenReturn("test-api-key");
        when(settings.getStringList("widget.allowed-origins", List.of())).thenReturn(List.of("https://oms.com"));
        when(settings.getStringList(eq("widget.allowed-scopes"), org.mockito.ArgumentMatchers.<List<String>>any()))
                .thenReturn(List.of("chat:stream", "rag:read"));
        when(settings.getInt("widget.token-ttl-seconds", 1800)).thenReturn(1800);
        when(settings.getInt("widget.token-max-ttl-seconds", 3600)).thenReturn(3600);
    }

    private String issue(WidgetTokenController.IssueRequest req) {
        ResponseEntity<?> response = controller.issueWidgetToken(req, request);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) ((ApiResponse<?>) Objects.requireNonNull(response.getBody())).data();
        return (String) body.get("token");
    }
}
