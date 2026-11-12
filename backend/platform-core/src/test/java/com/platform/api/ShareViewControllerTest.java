package com.platform.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.artifact.ArtifactService;
import com.platform.config.PlatformSettingsService;
import com.platform.config.WorkspaceProperties;
import com.platform.document.DocumentRenderer;
import com.platform.domain.ChatShareEntity;
import com.platform.policy.TokenBucketRateLimiter;
import com.platform.repository.ChatShareRepository;
import com.platform.session.ChatExportLabels;
import com.platform.session.ChatExportService;
import com.platform.session.ChatSessionOwnership;
import com.platform.share.ChatShareService;
import com.platform.share.ChatShareSettings;
import com.platform.tenant.TenantContext;
import com.platform.tenant.TenantDataSourceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** CR-182 TC-CHT-088: 받는 사람 공개 열기. */
class ShareViewControllerTest {

    private static final String TENANT = "t1";

    @TempDir Path root;

    private ChatShareRepository repository;
    private ChatShareSettings settings;
    private TenantDataSourceManager dataSources;
    private TokenBucketRateLimiter rateLimiter;
    private ShareViewController controller;
    private final Map<String, Integer> hits = new HashMap<>();
    private final List<String> tenantsSeen = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repository = mock(ChatShareRepository.class);
        when(repository.findByToken(anyString())).thenReturn(Optional.empty());
        when(repository.countView(any(UUID.class), any(OffsetDateTime.class))).thenAnswer(inv -> {
            tenantsSeen.add(TenantContext.getTenantId());
            return 1;
        });
        PlatformSettingsService platformSettings = mock(PlatformSettingsService.class);
        when(platformSettings.getString(anyString(), any())).thenAnswer(inv -> inv.getArgument(1));
        ChatExportLabels labels = new ChatExportLabels(platformSettings, new ObjectMapper());
        settings = mock(ChatShareSettings.class);
        when(settings.openRatePerMinute()).thenReturn(60);
        ChatShareService shareService = new ChatShareService(repository, mock(ChatSessionOwnership.class),
                mock(ChatExportService.class), labels, mock(DocumentRenderer.class), mock(ArtifactService.class),
                new WorkspaceProperties(), settings);

        dataSources = mock(TenantDataSourceManager.class);
        when(dataSources.getTenantDataSource(TENANT)).thenReturn(mock(DataSource.class));
        // Redis 토큰 버킷 대신 주소별 횟수로 같은 상한을 흉내 낸다.
        rateLimiter = mock(TokenBucketRateLimiter.class);
        when(rateLimiter.tryAcquire(eq("share-open"), anyString(), anyInt())).thenAnswer(inv -> {
            String address = inv.getArgument(1);
            int limit = inv.getArgument(2);
            int count = hits.merge(address, 1, Integer::sum);
            return count <= limit
                    ? TokenBucketRateLimiter.RateLimitResult.allowed(limit, limit - count)
                    : TokenBucketRateLimiter.RateLimitResult.exceeded(limit, count, 60);
        });
        controller = new ShareViewController(shareService, settings, dataSources, rateLimiter, labels);
    }

    @Test
    @DisplayName("TC-CHT-088 정상 대화 링크는 200 HTML 과 보안 머리글 5개, 열람 수를 올린다")
    void conversation_link_opens_with_secure_headers() throws IOException {
        ChatShareEntity share = share(ChatShareEntity.Kind.CONVERSATION, "conversation.html",
                "text/html; charset=UTF-8", "<p>공유 대화</p>");

        ResponseEntity<Resource> r = controller.open(TENANT, share.getToken(), request("203.0.113.7"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        HttpHeaders h = r.getHeaders();
        assertThat(h.getFirst("Content-Security-Policy")).isEqualTo(ShareViewController.CSP).startsWith("sandbox");
        assertThat(h.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(h.getFirst("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(h.getFirst("X-Robots-Tag")).isEqualTo("noindex");
        assertThat(h.getCacheControl()).isEqualTo("no-store");
        assertThat(h.getContentType().toString()).startsWith("text/html");
        assertThat(h.getContentDisposition().isAttachment()).isFalse();
        assertThat(body(r)).isEqualTo("<p>공유 대화</p>");
        verify(repository).countView(eq(share.getId()), any(OffsetDateTime.class));
        assertThat(tenantsSeen).containsExactly(TENANT);
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    @DisplayName("TC-CHT-088 정상 파일 링크는 200 attachment 로 내려준다")
    void file_link_is_attachment() throws IOException {
        ChatShareEntity share = share(ChatShareEntity.Kind.FILE, "보고서.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx");

        ResponseEntity<Resource> r = controller.open(TENANT, share.getToken(), request("203.0.113.7"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getHeaders().getContentDisposition().isAttachment()).isTrue();
        assertThat(r.getHeaders().getContentDisposition().getFilename()).isEqualTo("보고서.docx");
        assertThat(r.getHeaders().getFirst("Content-Security-Policy")).isEqualTo(ShareViewController.CSP);
    }

    @Test
    @DisplayName("TC-CHT-088 끊긴, 기한 지난, 없는 token, 없는 테넌트는 모두 같은 404 본문")
    void failures_share_one_404_page() throws IOException {
        ChatShareEntity revoked = share(ChatShareEntity.Kind.CONVERSATION, "conversation.html",
                "text/html; charset=UTF-8", "<p>끊김</p>");
        revoked.setRevokedAt(OffsetDateTime.now().minusMinutes(1));
        ChatShareEntity expired = share(ChatShareEntity.Kind.CONVERSATION, "conversation.html",
                "text/html; charset=UTF-8", "<p>지남</p>");
        expired.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
        String unknownToken = "A".repeat(43);

        List<ResponseEntity<Resource>> responses = List.of(
                controller.open(TENANT, revoked.getToken(), request("203.0.113.7")),
                controller.open(TENANT, expired.getToken(), request("203.0.113.7")),
                controller.open(TENANT, unknownToken, request("203.0.113.7")),
                controller.open("nope", revoked.getToken(), request("203.0.113.7")),
                controller.open("../t1", revoked.getToken(), request("203.0.113.7")));

        String first = body(responses.get(0));
        assertThat(first).contains("링크가 없거나 끝났습니다");
        for (ResponseEntity<Resource> r : responses) {
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(body(r)).isEqualTo(first);
            assertThat(r.getHeaders().getFirst("Content-Security-Policy")).isEqualTo(ShareViewController.CSP);
        }
        verify(repository, never()).countView(any(UUID.class), any(OffsetDateTime.class));
    }

    @Test
    @DisplayName("TC-CHT-088 같은 주소에서 분당 61번째 열기는 429")
    void sixty_first_open_in_a_minute_is_too_many() throws IOException {
        ChatShareEntity share = share(ChatShareEntity.Kind.CONVERSATION, "conversation.html",
                "text/html; charset=UTF-8", "<p>공유 대화</p>");

        for (int i = 1; i <= 60; i++) {
            assertThat(controller.open(TENANT, share.getToken(), request("203.0.113.7")).getStatusCode())
                    .as("열기 %d번째", i).isEqualTo(HttpStatus.OK);
        }
        ResponseEntity<Resource> blocked = controller.open(TENANT, share.getToken(), request("203.0.113.7"));
        ResponseEntity<Resource> otherAddress = controller.open(TENANT, share.getToken(), request("198.51.100.9"));

        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(blocked.getHeaders().getFirst("Content-Security-Policy")).isEqualTo(ShareViewController.CSP);
        assertThat(otherAddress.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(rateLimiter, times(62)).tryAcquire(eq("share-open"), anyString(), eq(60));
        verify(repository, times(61)).countView(eq(share.getId()), any(OffsetDateTime.class));
    }

    @Test
    @DisplayName("TC-CHT-088 주소는 X-Real-IP 를 먼저, 없으면 붙은 주소를 쓴다")
    void client_address_prefers_real_ip() {
        assertThat(ShareViewController.clientAddress(request("203.0.113.7"))).isEqualTo("203.0.113.7");
        MockHttpServletRequest direct = new MockHttpServletRequest();
        direct.setRemoteAddr("10.0.0.5");
        assertThat(ShareViewController.clientAddress(direct)).isEqualTo("10.0.0.5");
    }

    private ChatShareEntity share(ChatShareEntity.Kind kind, String fileName, String contentType, String content)
            throws IOException {
        UUID id = UUID.randomUUID();
        Path dir = Files.createDirectories(root.resolve(id.toString()));
        Path snapshot = Files.writeString(dir.resolve(fileName), content, StandardCharsets.UTF_8);
        ChatShareEntity s = new ChatShareEntity();
        s.setId(id);
        s.setToken(token(id));
        s.setKind(kind);
        s.setSessionId("s1");
        s.setFileName(kind == ChatShareEntity.Kind.FILE ? fileName : null);
        s.setSnapshotPath(snapshot.toString());
        s.setSnapshotSize(Files.size(snapshot));
        s.setContentType(contentType);
        s.setCreatedBy("user-a");
        s.setCreatedAt(OffsetDateTime.now().minusHours(1));
        s.setExpiresAt(OffsetDateTime.now().plusDays(7));
        when(repository.findByToken(s.getToken())).thenReturn(Optional.of(s));
        return s;
    }

    /** 43자 URL 안전 글자. 시험마다 달라야 해서 id 에서 만든다. */
    private static String token(UUID id) {
        String hex = id.toString().replace("-", "");
        return (hex + hex).substring(0, 43);
    }

    private static MockHttpServletRequest request(String realIp) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Real-IP", realIp);
        return req;
    }

    private static String body(ResponseEntity<Resource> r) throws IOException {
        assertThat(r.getBody()).isNotNull();
        try (var in = r.getBody().getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
