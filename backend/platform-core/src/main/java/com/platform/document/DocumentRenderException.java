package com.platform.document;

/**
 * 문서를 그리지 못한 까닭을 오류 코드 하나로 돌려준다. 도구 결과와 API 오류가 같은 코드를 쓴다(BIZ-210).
 */
public class DocumentRenderException extends RuntimeException {

    public static final String PDF_FONT_MISSING = "pdf_font_missing";

    private final String code;

    public DocumentRenderException(String code) {
        super(code);
        this.code = code;
    }

    public DocumentRenderException(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
