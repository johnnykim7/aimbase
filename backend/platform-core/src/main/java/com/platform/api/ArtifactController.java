package com.platform.api;

import com.platform.artifact.ArtifactPreviewService;
import com.platform.artifact.ArtifactService;
import com.platform.auth.UserPrincipal;
import com.platform.document.FileMediaTypes;
import com.platform.session.ChatRejectedException;
import com.platform.session.ChatSessionOwnership;
import com.platform.chatdoc.ChatDocumentService;
import com.platform.domain.ChatDocumentVersionEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * CR-132: CLI 산출물 다운로드 API.
 *
 * <p>세션 workspace 하위 {@code out/} 에 CLI 가 생성한 파일(pptx/xlsx/pdf/docx …)을 위젯이 내려받는다.
 * 인증은 chat 과 동일한 위젯 토큰({@code chat:upload} scope 재사용) + TenantContext 격리.
 * 세션→workspace 매핑이 현재 테넌트 DB 에서만 조회되므로 타 테넌트 파일은 노출되지 않는다.
 */
@RestController
@RequestMapping("/api/v1/chat/artifacts")
@Tag(name = "Chat Artifacts", description = "CR-132 CLI 산출물 다운로드 (pptx/xlsx/pdf/docx)")
public class ArtifactController {

    /** CR-180: 쪽 그림 주소에 위젯이 수정 시각을 붙여 부르므로 파일이 바뀌면 새 주소가 된다. */
    private static final CacheControl PAGE_CACHE = CacheControl.maxAge(1, TimeUnit.DAYS).cachePrivate();
    private static final String WIDGET_ROLE = "WIDGET";

    private final ArtifactService service;
    private final ArtifactPreviewService previewService;
    private final ChatSessionOwnership ownership;
    private final ChatDocumentService chatDocuments;

    public ArtifactController(ArtifactService service, ArtifactPreviewService previewService,
                              ChatSessionOwnership ownership, ChatDocumentService chatDocuments) {
        this.service = service;
        this.previewService = previewService;
        this.ownership = ownership;
        this.chatDocuments = chatDocuments;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_chat:upload') or isAuthenticated()")
    @Operation(summary = "세션 산출물 목록")
    public ApiResponse<Map<String, Object>> list(@AuthenticationPrincipal UserPrincipal principal,
                                                 @RequestParam("session_id") String sessionId) {
        requireOwner(principal, sessionId);
        List<ArtifactService.ArtifactInfo> artifacts = service.listArtifacts(sessionId);
        List<Map<String, Object>> items = artifacts.stream()
                .map(a -> artifactItem(sessionId, a))
                .toList();
        return ApiResponse.ok(Map.of("artifacts", items));
    }

    @GetMapping("/preview")
    @PreAuthorize("hasAuthority('SCOPE_chat:upload') or isAuthenticated()")
    @Operation(summary = "CR-180 산출물 미리보기 정보")
    public ApiResponse<Map<String, Object>> preview(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam("session_id") String sessionId,
            @RequestParam("name") String name,
            @RequestParam(value = "version", required = false) Integer version) {
        requireOwner(principal, sessionId);
        return ApiResponse.ok(previewService.info(sessionId, name, version));
    }

    @GetMapping("/preview/page")
    @PreAuthorize("hasAuthority('SCOPE_chat:upload') or isAuthenticated()")
    @Operation(summary = "CR-180 산출물 쪽 그림(PNG)")
    public ResponseEntity<Resource> previewPage(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam("session_id") String sessionId,
            @RequestParam("name") String name,
            @RequestParam("page") int page,
            @RequestParam(value = "version", required = false) Integer version) {
        requireOwner(principal, sessionId);
        Path png = previewService.page(sessionId, name, page, version);
        return ResponseEntity.ok()
                .cacheControl(PAGE_CACHE)
                .contentType(MediaType.IMAGE_PNG)
                .contentLength(sizeSafe(png))
                .body(new PathResource(png));
    }

    /** 목록 응답과 대화 끝 이벤트(done, result)의 artifacts[] 한 칸. 두 곳이 같은 모양을 쓴다. */
    public static Map<String, Object> artifactItem(String sessionId, ArtifactService.ArtifactInfo a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", a.name());
        m.put("size", a.size());
        m.put("download_url", downloadUrl(sessionId, a.name()));
        m.put("modified_at", a.modifiedAt().toString());
        m.put("preview_kind", a.previewKind().name());
        return m;
    }

    @GetMapping("/download")
    @PreAuthorize("hasAuthority('SCOPE_chat:upload') or isAuthenticated()")
    @Operation(summary = "산출물 파일 다운로드")
    public ResponseEntity<Resource> download(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam("session_id") String sessionId,
            @RequestParam("file") String file,
            @RequestParam(value = "version", required = false) Integer version) {
        requireOwner(principal, sessionId);

        if (version != null) return downloadVersion(sessionId, file, version);
        Path target;
        try {
            target = service.resolveDownloadPath(sessionId, file);
        } catch (ArtifactService.ArtifactAccessException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (ArtifactService.ArtifactNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }

        // 브라우저가 다운로드로 저장하도록 attachment + UTF-8 파일명(한글 산출물 대응).
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(target.getFileName().toString(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(FileMediaTypes.forName(target.getFileName().toString())))
                .contentLength(sizeSafe(target))
                .body(new PathResource(target));
    }

    /** CR-181: 옆 창 문서의 지난 판. 판 파일은 저장소에 있어 out/ 을 거치지 않는다. */
    private ResponseEntity<Resource> downloadVersion(String sessionId, String file, int version) {
        if (file == null || file.isBlank() || file.contains("/") || file.contains("\\")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "파일 이름에 경로 구분자: " + file);
        }
        ChatDocumentVersionEntity v = chatDocuments.liveFileVersion(sessionId, file, version)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "판 없음: " + file + " v" + version));
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file, StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(FileMediaTypes.forName(file)))
                .contentLength(v.getSizeBytes())
                .body(new InputStreamResource(chatDocuments.openFile(v)));
    }

    /** CR-132(할 일 3): done 이벤트에 실을 다운로드 URL — 상대 경로. 위젯이 baseUrl 앞에 붙인다. */
    static String downloadUrl(String sessionId, String fileName) {
        return "/api/v1/chat/artifacts/download"
                + "?session_id=" + urlEncode(sessionId)
                + "&file=" + urlEncode(fileName);
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static long sizeSafe(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return -1L;
        }
    }

    /**
     * CR-182 3.7: 위젯 토큰은 세션 주인만 연다. 공유 링크가 파일을 밖으로 내보내는 길이 생겨 남의 세션
     * 산출물을 먼저 막는다. 소비앱 키와 로그인은 지금처럼 테넌트 안에서 연다.
     */
    private void requireOwner(UserPrincipal principal, String sessionId) {
        if (principal != null && WIDGET_ROLE.equals(principal.getRole())
                && !ownership.ownedOrUnclaimed(principal.getId(), sessionId)) {
            throw new ChatRejectedException(HttpStatus.NOT_FOUND, "artifact_not_found");
        }
    }
}
