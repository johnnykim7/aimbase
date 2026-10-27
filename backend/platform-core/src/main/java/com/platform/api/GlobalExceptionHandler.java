package com.platform.api;

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

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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
