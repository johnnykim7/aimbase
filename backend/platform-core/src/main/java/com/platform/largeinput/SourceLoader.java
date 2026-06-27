package com.platform.largeinput;

/**
 * CR-120: 소스 로더 — MIME 별로 바이트를 {@link LargeInputSource} 로 적재.
 *
 * <p>MVP 는 PDF 만({@link PdfSourceLoader}). DOCX 등은 새 @Component 구현 1개만 추가하면
 * 분해기/엔진 무수정으로 확장된다(SourceLoader 자리만 둔 이유).
 */
public interface SourceLoader {

    /** 이 로더가 처리하는 MIME 인지. */
    boolean supports(String mimeType);

    /** attachment bytes(또는 인라인 텍스트)를 LargeInputSource 로 적재 (페이지 수 등 메타 채움). */
    LargeInputSource load(String sourceId, String mimeType, byte[] bytes);
}
