package com.platform.llm.claudecli;

/** CR-050: Claude CLI 워커/풀 실행 중 발생한 런타임 오류. */
public class ClaudeCliException extends RuntimeException {
    public ClaudeCliException(String message) { super(message); }
    public ClaudeCliException(String message, Throwable cause) { super(message, cause); }
}
