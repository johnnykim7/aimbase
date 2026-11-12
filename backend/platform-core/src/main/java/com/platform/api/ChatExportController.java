package com.platform.api;

import com.platform.auth.UserPrincipal;
import com.platform.session.ChatExportService;
import com.platform.session.ChatRejectedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/** CR-182 응답 저장(3.3)과 대화 내보내기(3.4). 그려서 바로 내려준다. */
@RestController
@RequestMapping("/api/v1/chat/sessions/{session_id}")
@Tag(name = "Chat Export", description = "CR-182 응답 저장, 대화 내보내기")
public class ChatExportController {

    private final ChatExportService exportService;

    public ChatExportController(ChatExportService exportService) {
        this.exportService = exportService;
    }

    @GetMapping("/turns/{turn_id}/export")
    @PreAuthorize("hasAuthority('SCOPE_chat:stream') or isAuthenticated()")
    @Operation(summary = "응답 하나를 PDF 또는 Word 로 저장")
    public ResponseEntity<byte[]> saveAnswer(@AuthenticationPrincipal UserPrincipal principal,
                                             @PathVariable("session_id") String sessionId,
                                             @PathVariable("turn_id") String turnId,
                                             @RequestParam(value = "format", required = false) String format,
                                             @RequestParam(value = "tz", required = false) String tz) {
        return attachment(exportService.saveAnswer(requester(principal), sessionId, turnId, format, tz));
    }

    @GetMapping("/export")
    @PreAuthorize("hasAuthority('SCOPE_chat:stream') or isAuthenticated()")
    @Operation(summary = "대화 전체를 PDF, Word, Markdown 으로 내보내기")
    public ResponseEntity<byte[]> exportConversation(@AuthenticationPrincipal UserPrincipal principal,
                                                     @PathVariable("session_id") String sessionId,
                                                     @RequestParam(value = "format", required = false) String format,
                                                     @RequestParam(value = "tz", required = false) String tz) {
        return attachment(exportService.exportConversation(requester(principal), sessionId, format, tz));
    }

    static ResponseEntity<byte[]> attachment(ChatExportService.Rendered file) {
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.bytes().length)
                .body(file.bytes());
    }

    static String requester(UserPrincipal principal) {
        if (principal == null || principal.getId() == null) {
            throw new ChatRejectedException(HttpStatus.UNAUTHORIZED, "unauthenticated");
        }
        return principal.getId();
    }
}
