package com.platform.rag;

import java.io.IOException;
import java.io.InputStream;

/**
 * 문서를 텍스트로 파싱하는 인터페이스.
 */
public interface DocumentParser {

    /**
     * InputStream에서 텍스트 추출.
     *
     * @param input    입력 스트림
     * @param mimeType MIME 타입 (예: "application/pdf", "text/plain")
     * @return 추출된 텍스트
     * @throws IOException 파싱 실패 시
     */
    String parse(InputStream input, String mimeType) throws IOException;

    /**
     * 이미 텍스트인 경우 그대로 반환.
     *
     * @param text 입력 텍스트
     * @return 동일한 텍스트
     */
    String parse(String text);
}
