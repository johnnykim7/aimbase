package com.platform.largeinput;

/**
 * CR-120: 분해 대상 소스 1건.
 *
 * @param sourceId   추적 식별자 (attachment_id 또는 인라인 식별자)
 * @param mimeType   MIME 타입 ("application/pdf", "text/plain" 등). SourceLoader 디스패치 키.
 * @param bytes      원본 바이트 (인라인 텍스트면 UTF-8 바이트). 텍스트 소스는 null 가능.
 * @param inlineText 인라인 텍스트 입력 (source_file 대신 input 으로 들어온 경우). 그 외 null.
 * @param totalPages PDF 등 페이지 수 (없으면 null).
 * @param filePath   작업장 절대경로(사이드카가 공유 볼륨에서 직접 읽을 수 있는 경로). CR-120:
 *                   값이 있으면 PDF 분해/렌더 시 base64 전송 대신 file_path 호출을 쓴다.
 *                   attachment 바이트 등 디스크 파일이 없는 소스는 null(base64 폴백).
 */
public record LargeInputSource(
        String sourceId,
        String mimeType,
        byte[] bytes,
        String inlineText,
        Integer totalPages,
        String filePath
) {
    /** SourceLoader 용 — filePath 미지정(null) 생성자. */
    public LargeInputSource(String sourceId, String mimeType, byte[] bytes,
                            String inlineText, Integer totalPages) {
        this(sourceId, mimeType, bytes, inlineText, totalPages, null);
    }

    public boolean isInlineText() { return inlineText != null; }

    /** 작업장 경로를 입힌 복제본 반환(executor 가 작업장 파일 적재 시 사용). */
    public LargeInputSource withFilePath(String path) {
        return new LargeInputSource(sourceId, mimeType, bytes, inlineText, totalPages, path);
    }
}
