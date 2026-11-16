package com.platform.session;

import org.springframework.http.HttpStatus;

/**
 * CR-175: 채팅 요청을 받지 않은 이유를 위젯이 갈라 볼 수 있는 코드로 돌려준다.
 * 응답은 ApiResponse.error(code) 이고 code 는 {@code session_busy}, {@code model_not_allowed} 처럼 고정 문자열이다.
 */
public class ChatRejectedException extends RuntimeException {

    private final HttpStatus status;

    public ChatRejectedException(HttpStatus status, String code) {
        super(code);
        this.status = status;
    }

    /** 원인을 남긴다. 연결 묶음은 원인 사슬을 보고 다음 구성원으로 갈지 정한다(CR-161). */
    public ChatRejectedException(HttpStatus status, String code, Throwable cause) {
        super(code, cause);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
