package com.platform.llm.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Map;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ContentBlock.Text.class, name = "text"),
        @JsonSubTypes.Type(value = ContentBlock.Image.class, name = "image"),
        @JsonSubTypes.Type(value = ContentBlock.Document.class, name = "document"),
        @JsonSubTypes.Type(value = ContentBlock.ToolUse.class, name = "tool_use"),
        @JsonSubTypes.Type(value = ContentBlock.ToolResult.class, name = "tool_result"),
        @JsonSubTypes.Type(value = ContentBlock.Structured.class, name = "structured"),
        @JsonSubTypes.Type(value = ContentBlock.Thinking.class, name = "thinking"),
})
public sealed interface ContentBlock
        permits ContentBlock.Text, ContentBlock.Image, ContentBlock.Document, ContentBlock.ToolUse,
                ContentBlock.ToolResult, ContentBlock.Structured, ContentBlock.Thinking {

    record Text(String text) implements ContentBlock {}

    /**
     * 이미지 블록 (PRD-111: 멀티모달 입력).
     * URL 또는 base64 데이터 중 하나로 이미지 전달.
     *
     * @param mediaType  MIME 타입 (e.g., "image/png", "image/jpeg")
     * @param data       base64 인코딩된 이미지 데이터 (URL 방식이면 null)
     * @param url        이미지 URL (base64 방식이면 null)
     */
    record Image(String mediaType, String data, String url) implements ContentBlock {
        /** base64 방식 팩토리 */
        public static Image ofBase64(String mediaType, String base64Data) {
            return new Image(mediaType, base64Data, null);
        }

        /** URL 방식 팩토리 */
        public static Image ofUrl(String url, String mediaType) {
            return new Image(mediaType, null, url);
        }

        /** 하위 호환: 기존 (mediaType, data) 생성자 */
        public Image(String mediaType, String data) {
            this(mediaType, data, null);
        }

        public boolean isBase64() { return data != null && !data.isBlank(); }
        public boolean isUrl() { return url != null && !url.isBlank(); }
    }

    /**
     * 문서 블록 (CR-061: PDF Vision 첨부).
     * 현재 PDF 만 지원. Anthropic Claude 는 네이티브 {@code document} 블록으로 전달되고,
     * 미지원 프로바이더는 ChatController 레이어에서 텍스트 추출 후 {@link Text} 로 폴백된다.
     *
     * @param mediaType  MIME 타입 (e.g., "application/pdf")
     * @param data       base64 인코딩된 파일 데이터 (URL 방식이면 null)
     * @param url        파일 URL (base64 방식이면 null)
     * @param filename   원본 파일명 (프롬프트 힌트용, nullable)
     */
    record Document(String mediaType, String data, String url, String filename) implements ContentBlock {
        public static Document ofBase64(String mediaType, String base64Data, String filename) {
            return new Document(mediaType, base64Data, null, filename);
        }

        public boolean isBase64() { return data != null && !data.isBlank(); }
        public boolean isUrl() { return url != null && !url.isBlank(); }
    }

    record ToolUse(String id, String name, Map<String, Object> input) implements ContentBlock {}

    record ToolResult(String toolUseId, String content) implements ContentBlock {}

    /** 구조화된 출력 블록 (CR-007). LLM이 JSON Schema에 맞춰 반환한 구조화 데이터. */
    record Structured(String schema, Map<String, Object> data) implements ContentBlock {}

    /** Extended Thinking 블록 (CR-030). LLM 내부 추론 과정 + 서명. */
    record Thinking(String thinking, String signature) implements ContentBlock {}
}
