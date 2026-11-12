package com.platform.image;

/** CR-184: 공급처 호출 실패. code 는 위젯이 문구로 바꾸는 고정 문자열이다(T3-2 공급처 실패 코드). */
public class ImageProviderException extends RuntimeException {

    public static final String AUTH = "image_provider_auth";
    public static final String QUOTA = "image_provider_quota";
    public static final String REJECTED = "image_rejected";
    public static final String TIMEOUT = "image_timeout";
    public static final String FAILED = "image_failed";

    private final String code;

    public ImageProviderException(String code, String detail) {
        super(code + (detail != null ? ": " + detail : ""));
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
