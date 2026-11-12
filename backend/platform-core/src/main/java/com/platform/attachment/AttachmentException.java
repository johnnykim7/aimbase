package com.platform.attachment;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * CR-061 첨부 처리 전용 에러. 코드 체계(FG-ATT-4xxx) 는 가이드에 노출된다.
 *
 * <p>CR-183: 위젯이 코드로 문구를 고르고 「최대 N」 을 보이도록 크기, 행, 열 상한 거절은 상한 값을 싣는다(BIZ-220).
 */
public class AttachmentException extends ResponseStatusException {

    public static final String CODE_MIME_UNSUPPORTED   = "FG-ATT-4001";
    public static final String CODE_SIZE_EXCEEDED      = "FG-ATT-4002";
    public static final String CODE_ENCRYPTED          = "FG-ATT-4003";
    public static final String CODE_UNREADABLE         = "FG-ATT-4004";
    public static final String CODE_ROWS_EXCEEDED      = "FG-ATT-4005";
    public static final String CODE_COLUMNS_EXCEEDED   = "FG-ATT-4006";
    public static final String CODE_OWNERSHIP_DENIED   = "FG-ATT-4031";
    public static final String CODE_COUNT_EXCEEDED     = "FG-ATT-4091";
    public static final String CODE_MIME_MISMATCH      = "FG-ATT-4221";
    public static final String CODE_NOT_FOUND          = "FG-ATT-4041";

    private final String code;
    private final String detail;
    private final Long limit;

    public AttachmentException(HttpStatus status, String code, String reason) {
        this(status, code, reason, null);
    }

    public AttachmentException(HttpStatus status, String code, String reason, Long limit) {
        super(status, "[" + code + "] " + reason);
        this.code = code;
        this.detail = reason;
        this.limit = limit;
    }

    public String getCode() {
        return code;
    }

    /** 코드 머리를 뺀 사유 글. */
    public String getDetail() {
        return detail;
    }

    /** 크기, 행, 열 상한으로 거절했을 때의 상한. 그 밖은 null. */
    public Long getLimit() {
        return limit;
    }
}
