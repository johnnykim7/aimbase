package com.platform.largeinput;

/**
 * CR-120: 분해 대상 소스 1건.
 *
 * @param sourceId   추적 식별자 (attachment_id 또는 인라인 식별자)
 * @param mimeType   MIME 타입 ("application/pdf", "text/plain" 등). SourceLoader 디스패치 키.
 * @param bytes      원본 바이트 (인라인 텍스트면 UTF-8 바이트). 텍스트 소스는 null 가능.
 * @param inlineText 인라인 텍스트 입력 (source_file 대신 input 으로 들어온 경우). 그 외 null.
 * @param totalPages PDF 등 페이지 수 (없으면 null).
 */
public record LargeInputSource(
        String sourceId,
        String mimeType,
        byte[] bytes,
        String inlineText,
        Integer totalPages
) {
    public boolean isInlineText() { return inlineText != null; }
}
