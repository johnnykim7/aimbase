package com.platform.skill;

/** CR-147: 실행 컨테이너에 닿지 못했거나 실행 컨테이너가 요청을 거부했다. */
public class SkillSandboxException extends RuntimeException {

    public SkillSandboxException(String message) {
        super(message);
    }

    public SkillSandboxException(String message, Throwable cause) {
        super(message, cause);
    }
}
