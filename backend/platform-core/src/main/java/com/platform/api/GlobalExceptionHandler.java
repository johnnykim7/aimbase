package com.platform.api;

import com.platform.document.DocumentRenderException;
import com.platform.session.ChatRejectedException;
import com.platform.llm.adapter.CliAccountLimitedException;
import com.platform.session.BlockingLimitException;
import com.platform.skill.SkillNotFoundException;
import com.platform.tenant.quota.QuotaExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** CR-185: 이미 정해진 승인은 지금 상태를 data.status 로 같이 돌려준다. */
    @ExceptionHandler(com.platform.orchestrator.approval.ApprovalDecisionException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleApprovalDecision(
            com.platform.orchestrator.approval.ApprovalDecisionException ex) {
        Map<String, String> data = ex.getCurrentStatus() != null ? Map.of("status", ex.getCurrentStatus()) : null;
        return ResponseEntity.status(ex.getStatus()).body(new ApiResponse<>(false, data, ex.getMessage(), null));
    }

    @ExceptionHandler(ChatRejectedException.class)
    public ResponseEntity<ApiResponse<Void>> handleChatRejected(ChatRejectedException ex) {
        return ResponseEntity.status(ex.getStatus()).body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * CR-183: 첨부 오류. 위젯이 code 로 문구를 고르고, 크기, 행, 열 상한 거절은 limit 으로 「최대 N」 을 보인다(BIZ-220).
     */
    @ExceptionHandler(com.platform.attachment.AttachmentException.class)
    public ResponseEntity<java.util.Map<String, Object>> handleAttachment(com.platform.attachment.AttachmentException ex) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("success", false);
        body.put("error", ex.getDetail());
        body.put("code", ex.getCode());
        if (ex.getLimit() != null) body.put("limit", ex.getLimit());
        return ResponseEntity.status(ex.getStatusCode()).body(body);
    }

    /** CR-182: PDF 글꼴이 없을 때만 503 이고, 그 밖의 그리기 실패는 서버 오류다. */
    @ExceptionHandler(DocumentRenderException.class)
    public ResponseEntity<ApiResponse<Void>> handleDocumentRender(DocumentRenderException ex) {
        HttpStatus status = DocumentRenderException.PDF_FONT_MISSING.equals(ex.getCode())
                ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.INTERNAL_SERVER_ERROR;
        log.warn("Document render failed: {}", ex.getCode());
        return ResponseEntity.status(status).body(ApiResponse.error(ex.getCode()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalState(IllegalStateException ex) {
        log.warn("IllegalStateException: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * CR-161: 단일 커넥션의 CLI 계정 한도. 엔진이 RuntimeException 으로 감싸 던지지만 Spring 이 cause 사슬까지
     * 찾아 이 처리기로 온다. 연결 묶음 요청은 다음 멤버로 넘기고, 다 막히면 connection_group_unavailable 이다.
     */
    @ExceptionHandler(CliAccountLimitedException.class)
    public ResponseEntity<ApiResponse<Void>> handleCliAccountLimited(CliAccountLimitedException ex) {
        log.warn("CLI account limited: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("IllegalArgumentException: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /** CR-147: 스킬 묶음 이름, 판, 파일을 찾지 못함 (BIZ-120). */
    @ExceptionHandler(SkillNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleSkillNotFound(SkillNotFoundException ex) {
        log.warn("Skill not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleQuotaExceeded(QuotaExceededException ex) {
        log.warn("Quota exceeded: {} (quota={}, usage={}, requested={})",
                ex.getMessage(), ex.getQuota(), ex.getCurrentUsage(), ex.getRequested());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(BlockingLimitException.class)
    public ResponseEntity<ApiResponse<Void>> handleBlockingLimit(BlockingLimitException ex) {
        log.warn("Context window blocking limit: usage={}%", String.format("%.1f", ex.getUsageRatio() * 100));
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(NonTransientAiException.class)
    public ResponseEntity<ApiResponse<Void>> handleNonTransientAi(NonTransientAiException ex) {
        log.warn("AI service error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error("AI service unavailable: " + ex.getMessage()));
    }
}
