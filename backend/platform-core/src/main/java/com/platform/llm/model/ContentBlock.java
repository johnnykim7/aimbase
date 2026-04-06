package com.platform.llm.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Map;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ContentBlock.Text.class, name = "text"),
        @JsonSubTypes.Type(value = ContentBlock.Image.class, name = "image"),
        @JsonSubTypes.Type(value = ContentBlock.ToolUse.class, name = "tool_use"),
        @JsonSubTypes.Type(value = ContentBlock.ToolResult.class, name = "tool_result"),
        @JsonSubTypes.Type(value = ContentBlock.Structured.class, name = "structured"),
        @JsonSubTypes.Type(value = ContentBlock.Thinking.class, name = "thinking"),
})
public sealed interface ContentBlock
        permits ContentBlock.Text, ContentBlock.Image, ContentBlock.ToolUse, ContentBlock.ToolResult,
                ContentBlock.Structured, ContentBlock.Thinking {

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

    record ToolUse(String id, String name, Map<String, Object> input) implements ContentBlock {}

    record ToolResult(String toolUseId, String content) implements ContentBlock {}

    /** 구조화된 출력 블록 (CR-007). LLM이 JSON Schema에 맞춰 반환한 구조화 데이터. */
    record Structured(String schema, Map<String, Object> data) implements ContentBlock {}

    /** Extended Thinking 블록 (CR-030). LLM 내부 추론 과정 + 서명. */
    record Thinking(String thinking, String signature) implements ContentBlock {}
}
