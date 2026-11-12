package com.platform.api;

import com.platform.domain.ChatAttachmentEntity;
import com.platform.session.ChatOwner;
import com.platform.session.ChatProjectService;
import com.platform.session.ChatRejectedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CR-178: 위젯 프로젝트와 프로젝트 자료(PRD-423, PRD-425). 남의 프로젝트는 없는 것으로 보고 404 다(BIZ-171).
 */
@RestController
@RequestMapping("/api/v1/chat/projects")
@Tag(name = "Chat Projects", description = "CR-178 위젯 프로젝트")
public class ChatProjectController {

    private static final String UPLOAD_SCOPE = "SCOPE_chat:upload";

    private final ChatProjectService projectService;

    public ChatProjectController(ChatProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_chat:stream') or isAuthenticated()")
    @Operation(summary = "내 프로젝트 목록")
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(projectService.list(ChatHistoryController.requireOwner()));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_chat:stream') or isAuthenticated()")
    @Operation(summary = "프로젝트 만들기")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(projectService.create(ChatHistoryController.requireOwner(), body)));
    }

    @GetMapping("/{projectId}")
    @PreAuthorize("hasAuthority('SCOPE_chat:stream') or isAuthenticated()")
    @Operation(summary = "프로젝트 한 건(지시문 전문, 자료 목록)")
    public ApiResponse<Map<String, Object>> get(@PathVariable String projectId) {
        return ApiResponse.ok(projectService.get(ChatHistoryController.requireOwner(), projectId));
    }

    @PatchMapping("/{projectId}")
    @PreAuthorize("hasAuthority('SCOPE_chat:stream') or isAuthenticated()")
    @Operation(summary = "프로젝트 이름, 지시문 바꾸기")
    public ApiResponse<Map<String, Object>> update(@PathVariable String projectId,
                                                   @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(projectService.update(ChatHistoryController.requireOwner(), projectId, body));
    }

    @DeleteMapping("/{projectId}")
    @PreAuthorize("hasAuthority('SCOPE_chat:stream') or isAuthenticated()")
    @Operation(summary = "프로젝트 삭제(대화는 남는다)")
    public ResponseEntity<Void> delete(@PathVariable String projectId) {
        projectService.delete(ChatHistoryController.requireOwner(), projectId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{projectId}/files")
    @PreAuthorize("hasAuthority('SCOPE_chat:stream') or isAuthenticated()")
    @Operation(summary = "프로젝트 자료 목록")
    public ApiResponse<List<Map<String, Object>>> files(@PathVariable String projectId) {
        return ApiResponse.ok(projectService.listFiles(ChatHistoryController.requireOwner(), projectId));
    }

    @PostMapping(value = "/{projectId}/files", consumes = "multipart/form-data")
    @PreAuthorize("hasAuthority('SCOPE_chat:upload') or isAuthenticated()")
    @Operation(summary = "프로젝트 자료 올리기")
    public ResponseEntity<ApiResponse<Map<String, Object>>> upload(@PathVariable String projectId,
                                                                   @RequestParam("file") MultipartFile file) {
        String owner = ChatHistoryController.requireOwner();
        requireUploadScope();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(projectService.uploadFile(owner, projectId, file)));
    }

    @DeleteMapping("/{projectId}/files/{fileId}")
    @PreAuthorize("hasAuthority('SCOPE_chat:stream') or isAuthenticated()")
    @Operation(summary = "프로젝트 자료 삭제")
    public ResponseEntity<Void> deleteFile(@PathVariable String projectId, @PathVariable String fileId) {
        projectService.deleteFile(ChatHistoryController.requireOwner(), projectId, fileId);
        return ResponseEntity.noContent().build();
    }

    /** 자료를 복사해 이 대화의 첨부로 붙인다. 응답은 첨부 올리기와 같다. */
    @PostMapping("/{projectId}/files/{fileId}/attach")
    @PreAuthorize("hasAuthority('SCOPE_chat:upload') or isAuthenticated()")
    @Operation(summary = "프로젝트 자료를 이 대화의 첨부로 붙이기")
    public ResponseEntity<ApiResponse<Map<String, Object>>> attach(@PathVariable String projectId,
                                                                   @PathVariable String fileId,
                                                                   @RequestBody Map<String, Object> body) {
        String owner = ChatHistoryController.requireOwner();
        requireUploadScope();
        Object sessionId = body != null ? body.get("session_id") : null;
        ChatAttachmentEntity saved = projectService.attachFile(owner, projectId, fileId,
                sessionId instanceof String s ? s : null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("attachment_id", saved.getId().toString());
        out.put("filename", saved.getFilename());
        out.put("media_type", saved.getMediaType());
        out.put("size_bytes", saved.getSizeBytes());
        out.put("pages", saved.getPages());
        out.put("expires_at", saved.getExpiresAt().toString());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(out));
    }

    /** 위젯 토큰은 chat:upload 가 있어야 파일을 올린다. 로그인은 그대로 통과한다. */
    private static void requireUploadScope() {
        if (!ChatOwner.isWidget(ChatOwner.currentPrincipal())) return;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean granted = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> UPLOAD_SCOPE.equals(a.getAuthority()));
        if (!granted) throw new ChatRejectedException(HttpStatus.FORBIDDEN, "scope_required:chat:upload");
    }
}
