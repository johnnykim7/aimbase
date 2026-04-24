package com.platform.speech;

/**
 * CR-060: Speech(STT/TTS) 프록시 과정에서 발생하는 도메인 예외.
 *
 * HTTP 상태 매핑은 컨트롤러 레이어에서 {@link ErrorCode} 기준으로 수행한다.
 */
public class SpeechException extends RuntimeException {

    public enum ErrorCode {
        PROVIDER_UNAVAILABLE,
        UPSTREAM_ERROR,
        TIMEOUT
    }

    private final ErrorCode code;

    public SpeechException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public SpeechException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }

    public static SpeechException providerUnavailable() {
        return new SpeechException(ErrorCode.PROVIDER_UNAVAILABLE,
                "OpenAI connection not configured");
    }

    public static SpeechException upstream(int status, String body) {
        return new SpeechException(ErrorCode.UPSTREAM_ERROR,
                "Upstream error (HTTP " + status + "): " + body);
    }

    public static SpeechException timeout(Throwable cause) {
        return new SpeechException(ErrorCode.TIMEOUT, "Upstream timeout", cause);
    }
}
