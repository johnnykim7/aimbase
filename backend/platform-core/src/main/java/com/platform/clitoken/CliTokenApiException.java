package com.platform.clitoken;

import org.springframework.http.HttpStatus;

/** CR-196: 1년 토큰 API 의 거절. message 는 화면이 그대로 받는 오류 코드다(T3-2). 토큰 값은 싣지 않는다. */
public class CliTokenApiException extends RuntimeException {

    private final HttpStatus status;

    public CliTokenApiException(HttpStatus status, String code) {
        super(code);
        this.status = status;
    }

    public HttpStatus getStatus() { return status; }
}
