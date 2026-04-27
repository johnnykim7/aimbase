package com.platform.runner.claudecli;

/** CR-050: Claude CLI 턴 타임아웃 — 결정 7에 따라 상위에서 run 실패로 전파. */
public class ClaudeCliTimeoutException extends ClaudeCliException {
    public ClaudeCliTimeoutException(String message) { super(message); }
}
