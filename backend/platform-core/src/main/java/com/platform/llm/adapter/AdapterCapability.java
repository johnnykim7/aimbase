package com.platform.llm.adapter;

/**
 * CR-061: 프로바이더별 멀티모달 입력 지원 여부.
 *
 * ChatController 가 {@code attachment_id} 를 ContentBlock 으로 변환할 때
 * 어댑터의 capability 를 확인해 네이티브 Vision / Document 블록을 쓸지,
 * 아니면 텍스트 추출 폴백을 쓸지 결정한다.
 */
public record AdapterCapability(boolean supportsImage, boolean supportsPdf) {

    public static final AdapterCapability IMAGE_ONLY = new AdapterCapability(true, false);
    public static final AdapterCapability IMAGE_AND_PDF = new AdapterCapability(true, true);
    public static final AdapterCapability NONE = new AdapterCapability(false, false);
}
