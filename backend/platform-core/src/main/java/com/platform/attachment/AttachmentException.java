package com.platform.attachment;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * CR-061 첨부 처리 전용 에러. 코드 체계(FG-ATT-4xxx) 는 가이드에 노출된다.
 */
public class AttachmentException extends ResponseStatusException {

    public static final String CODE_MIME_UNSUPPORTED   = "FG-ATT-4001";
    public static final String CODE_SIZE_EXCEEDED      = "FG-ATT-4002";
    public static final String CODE_OWNERSHIP_DENIED   = "FG-ATT-4031";
    public static final String CODE_COUNT_EXCEEDED     = "FG-ATT-4091";
    public static final String CODE_MIME_MISMATCH      = "FG-ATT-4221";
    public static final String CODE_NOT_FOUND          = "FG-ATT-4041";

    private final String code;

    public AttachmentException(HttpStatus status, String code, String reason) {
        super(status, "[" + code + "] " + reason);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
