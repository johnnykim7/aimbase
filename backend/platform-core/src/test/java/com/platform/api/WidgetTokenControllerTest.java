package com.platform.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.auth.JwtProvider;
import com.platform.config.PlatformSettingsService;
import com.platform.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * CR-058: WidgetTokenController 발급 정책 검증.
 */
@ExtendWith(MockitoExtension.class)
class WidgetTokenControllerTest {

    @Mock private PlatformSettingsService settings;
    @Mock private HttpServletRequest request;

    private JwtProvider jwtProvider;
    private WidgetTokenController controller;

    @BeforeEach
    void setUp() {
        jwtProvider = new JwtProvider(
                "cr058-widget-controller-test-secret-which-is-long-enough-1234",
                1800_000L, 604800_000L);
        controller = new WidgetTokenController(jwtProvider, settings);
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
}
