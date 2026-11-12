package com.platform.api;

import com.platform.document.DocumentRenderer;
import com.platform.domain.ChatShareEntity;
import com.platform.policy.TokenBucketRateLimiter;
import com.platform.session.ChatExportLabels;
import com.platform.share.ChatShareService;
import com.platform.share.ChatShareSettings;
import com.platform.tenant.TenantContext;
import com.platform.tenant.TenantDataSourceManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Optional;

/**
 * 받는 사람이 공유 링크를 연다(CR-182 3.5.3). 로그인 없이 열리고, 테넌트는 머리글이 아니라 경로로 고른다.
 * 실패 까닭은 모두 같은 404 화면으로 감춰 링크를 더듬어 찾지 못하게 한다.
 */
@RestController
@Tag(name = "Chat Share View", description = "CR-182 공유 링크 열기 (공개)")
public class ShareViewController {

    static final String CSP = "sandbox; default-src 'none'; style-src 'unsafe-inline'; img-src data:";
    private static final String RATE_BUCKET = "share-open";

    private static final Logger log = LoggerFactory.getLogger(ShareViewController.class);

    private final ChatShareService shareService;
    private final ChatShareSettings settings;
    private final TenantDataSourceManager tenantDataSources;
    private final TokenBucketRateLimiter rateLimiter;
    private final ChatExportLabels labels;

    public ShareViewController(ChatShareService shareService, ChatShareSettings settings,
                               TenantDataSourceManager tenantDataSources, TokenBucketRateLimiter rateLimiter,
                               ChatExportLabels labels) {
        this.shareService = shareService;
        this.settings = settings;
        this.tenantDataSources = tenantDataSources;
        this.rateLimiter = rateLimiter;
        this.labels = labels;
    }

    @GetMapping("/share/{tenant_id}/{token}")
    @Operation(summary = "공유 링크 열기 (공개)")
    public ResponseEntity<Resource> open(@PathVariable("tenant_id") String tenantId,
                                         @PathVariable("token") String token,
                                         HttpServletRequest request) {
        if (!rateLimiter.tryAcquire(RATE_BUCKET, clientAddress(request), settings.openRatePerMinute()).allowed()) {
            return page(HttpStatus.TOO_MANY_REQUESTS);
        }
        if (!ChatShareService.TENANT_ID.matcher(tenantId).matches()
                || tenantDataSources.getTenantDataSource(tenantId) == null) {
            return page(HttpStatus.NOT_FOUND);
        }
        Optional<ChatShareService.Opened> opened;
        TenantContext.setTenantId(tenantId);
        try {
            opened = shareService.open(token);
        } catch (RuntimeException e) {
            log.warn("Share open failed tenant={}: {}", tenantId, e.getMessage());
            opened = Optional.empty();
        } finally {
            TenantContext.clear();
        }
        if (opened.isEmpty()) return page(HttpStatus.NOT_FOUND);

        ChatShareService.Opened o = opened.get();
        HttpHeaders headers = secureHeaders();
        if (o.kind() == ChatShareEntity.Kind.FILE) {
            headers.setContentDisposition(ContentDisposition.attachment()
                    .filename(o.fileName(), StandardCharsets.UTF_8).build());
        }
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType(o.contentType()))
                .contentLength(sizeSafe(o))
                .body(new PathResource(o.path()));
    }

    /** 운영은 nginx 가 X-Real-IP 에 보낸 주소를 싣는다. 없으면 바로 붙은 주소. */
    static String clientAddress(HttpServletRequest request) {
        String real = request.getHeader("X-Real-IP");
        return real != null && !real.isBlank() ? real.strip() : request.getRemoteAddr();
    }

    private ResponseEntity<Resource> page(HttpStatus status) {
        String text = DocumentRenderer.escape(labels.current().get(ChatExportLabels.LINK_GONE));
        String html = "<!DOCTYPE html><html><head><meta charset=\"UTF-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>" + text + "</title></head>"
                + "<body style=\"font-family:sans-serif;color:#333;display:flex;align-items:center;"
                + "justify-content:center;min-height:90vh;margin:0\"><p>" + text + "</p></body></html>";
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.status(status)
                .headers(secureHeaders())
                .contentType(MediaType.parseMediaType("text/html; charset=UTF-8"))
                .contentLength(body.length)
                .body(new ByteArrayResource(body));
    }

    private static HttpHeaders secureHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.set("Content-Security-Policy", CSP);
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Referrer-Policy", "no-referrer");
        h.set("X-Robots-Tag", "noindex");
        h.setCacheControl("no-store");
        return h;
    }

    private static long sizeSafe(ChatShareService.Opened o) {
        try {
            return Files.size(o.path());
        } catch (IOException e) {
            return -1L;
        }
    }
}
