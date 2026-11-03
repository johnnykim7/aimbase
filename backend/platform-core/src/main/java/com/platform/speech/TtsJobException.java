package com.platform.speech;

/** CR-166: 음성 생성 창구가 돌려줄 HTTP 상태와 오류 코드(T3-2 「같은 목소리 음성 생성 작업」 오류 표). */
public class TtsJobException extends RuntimeException {

    private final int status;
    private final String code;

    public TtsJobException(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public int getStatus() { return status; }
    public String getCode() { return code; }
}
