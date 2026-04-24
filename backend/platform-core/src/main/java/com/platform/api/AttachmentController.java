package com.platform.api;

import com.platform.attachment.AttachmentService;
import com.platform.domain.ChatAttachmentEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * CR-061 위젯 파일 첨부 API.
 * Scope = {@code chat:upload}. 세션 소유권 검증은 서비스 레이어에서 일괄.
 */
@RestController
@RequestMapping("/api/v1/chat/attachments")
@Tag(name = "Chat Attachments", description = "CR-061 위젯 파일 첨부 (이미지/PDF)")
public class AttachmentController {

    private final AttachmentService service;

    public AttachmentController(AttachmentService service) {
        this.service = service;
    }

    @PostMapping(consumes = "multipart/form-data")
    @PreAuthorize("hasAuthority('SCOPE_chat:upload') or isAuthenticated()")
    @Operation(summary = "첨부 파일 업로드 (이미지/PDF)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> upload(
            @RequestParam("session_id") String sessionId,
            @RequestParam("file") MultipartFile file) {

        ChatAttachmentEntity saved = service.save(sessionId, file);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("attachment_id", saved.getId().toString());
        body.put("filename", saved.getFilename());
        body.put("media_type", saved.getMediaType());
        body.put("size_bytes", saved.getSizeBytes());
        body.put("pages", saved.getPages());
        body.put("expires_at", saved.getExpiresAt().toString());
        return ResponseEntity.status(201).body(ApiResponse.ok(body));
    }

    @DeleteMapping("/{attachmentId}")
    @PreAuthorize("hasAuthority('SCOPE_chat:upload') or isAuthenticated()")
    @Operation(summary = "첨부 파일 삭제 (세션 소유권 필요)")
    public ResponseEntity<Void> delete(
            @PathVariable("attachmentId") UUID attachmentId,
            @RequestParam("session_id") String sessionId) {
        service.delete(attachmentId, sessionId);
        return ResponseEntity.noContent().build();
    }
}
