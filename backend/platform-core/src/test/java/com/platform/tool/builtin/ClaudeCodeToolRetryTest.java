package com.platform.tool.builtin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CR-043: ClaudeCodeTool.isRetryableFailure 분기 검증.
 * 재시도 대상 에러(인증/레이트리밋/5xx)와 비재시도(사용자 오류) 분류 정책 테스트.
 */
class ClaudeCodeToolRetryTest {

    @Test
    void retryable_auth_errors() {
        assertTrue(ClaudeCodeTool.isRetryableFailure(1, "HTTP 401 Unauthorized"));
        assertTrue(ClaudeCodeTool.isRetryableFailure(1, "invalid api key"));
        assertTrue(ClaudeCodeTool.isRetryableFailure(1, "OAuth token expired"));
        assertTrue(ClaudeCodeTool.isRetryableFailure(1, "authentication failed"));
    }

    @Test
    void retryable_rate_limit() {
        assertTrue(ClaudeCodeTool.isRetryableFailure(1, "HTTP 429 Too Many Requests"));
        assertTrue(ClaudeCodeTool.isRetryableFailure(1, "rate limit exceeded"));
        assertTrue(ClaudeCodeTool.isRetryableFailure(1, "Quota exceeded for this month"));
        assertTrue(ClaudeCodeTool.isRetryableFailure(1, "Overloaded, try again"));
    }

    @Test
    void retryable_server_errors() {
        assertTrue(ClaudeCodeTool.isRetryableFailure(1, "500 Internal Server Error"));
        assertTrue(ClaudeCodeTool.isRetryableFailure(1, "502 Bad Gateway"));
        assertTrue(ClaudeCodeTool.isRetryableFailure(1, "503 Service Unavailable"));
        assertTrue(ClaudeCodeTool.isRetryableFailure(1, "504 Gateway Timeout"));
        assertTrue(ClaudeCodeTool.isRetryableFailure(1, "Connection reset by peer"));
    }

    @Test
    void non_retryable_user_errors() {
        assertFalse(ClaudeCodeTool.isRetryableFailure(1, "Error: prompt is required"));
        assertFalse(ClaudeCodeTool.isRetryableFailure(1, "File not found: /tmp/missing.txt"));
        assertFalse(ClaudeCodeTool.isRetryableFailure(1, "Tool 'Bash' not allowed"));
        assertFalse(ClaudeCodeTool.isRetryableFailure(1, "Syntax error in json-schema"));
    }

    @Test
    void non_retryable_empty_output() {
        assertFalse(ClaudeCodeTool.isRetryableFailure(1, null));
        assertFalse(ClaudeCodeTool.isRetryableFailure(1, ""));
    }
}
