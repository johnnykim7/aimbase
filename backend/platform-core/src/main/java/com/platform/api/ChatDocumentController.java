package com.platform.api;

import com.platform.chatdoc.ChatDocumentService;
import com.platform.chatdoc.DocumentEditService;
import com.platform.domain.ChatDocumentEntity;
import com.platform.domain.ChatDocumentVersionEntity;
import com.platform.session.ChatRejectedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CR-181 PRD-481, PRD-484: 옆 창 문서의 판 조회와 되돌리기. 문서는 현재 테넌트 DB 에서만 찾는다.
 */
@RestController
@RequestMapping("/api/v1/chat/documents")
@Tag(name = "Chat Documents", description = "위젯 옆 창 문서와 판")
public class ChatDocumentController {

    private final ChatDocumentService documents;
    private final DocumentEditService documentEditService;

    public ChatDocumentController(ChatDocumentService documents, DocumentEditService documentEditService) {
        this.documents = documents;
        this.documentEditService = documentEditService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_chat:stream') or isAuthenticated()")
    @Operation(summary = "세션의 문서와 판 목록", description = "지운 판은 빼고 준다.")
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam("session_id") String sessionId) {
        return ApiResponse.ok(documents.listForSession(sessionId));
    }

    @PostMapping("/{documentId}/versions/{version}/restore")
    @PreAuthorize("hasAuthority('SCOPE_chat:stream') or isAuthenticated()")
    @Operation(summary = "앞 판으로 되돌리기", description = "그 판 내용을 마지막 판 다음 번호로 복사한다. 마지막 판과 같으면 UNCHANGED.")
    public ApiResponse<Map<String, Object>> restore(@PathVariable String documentId, @PathVariable int version) {
        return ApiResponse.ok(documentEditService.restore(parseId(documentId), version, currentUserId()).toMap());
    }

    @GetMapping("/{documentId}/versions/{version}")
    @PreAuthorize("hasAuthority('SCOPE_chat:stream') or isAuthenticated()")
    @Operation(summary = "판 내용", description = "BLOCK 은 {lang, content}, FILE 은 파일 내려받기.")
    public ResponseEntity<?> version(@PathVariable String documentId, @PathVariable int version) {
        UUID id = parseId(documentId);
        ChatDocumentEntity doc = documents.find(id).orElseThrow(ChatDocumentController::notFound);
        ChatDocumentVersionEntity v = documents.liveVersion(id, version).orElseThrow(ChatDocumentController::notFound);

        if (doc.getKind() == ChatDocumentEntity.Kind.BLOCK) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("lang", doc.getLang());
            body.put("content", v.getContent());
            return ResponseEntity.ok(ApiResponse.ok(body));
        }
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(doc.getName(), StandardCharsets.UTF_8)
                .build();
        MediaType type = MediaTypeFactory.getMediaType(doc.getName()).orElse(MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(type)
                .contentLength(v.getSizeBytes())
                .body(new InputStreamResource(documents.openFile(v)));
    }

    private static UUID parseId(String documentId) {
        try {
            return UUID.fromString(documentId);
        } catch (IllegalArgumentException e) {
            throw notFound();
        }
    }

    private static String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        if (auth.getPrincipal() instanceof com.platform.auth.UserPrincipal up) return up.getUsername();
        return auth.getName();
    }

    private static ChatRejectedException notFound() {
        return new ChatRejectedException(HttpStatus.NOT_FOUND, "document_not_found");
    }
}
