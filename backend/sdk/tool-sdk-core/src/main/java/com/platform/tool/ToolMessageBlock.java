package com.platform.tool;

/**
 * CR-095: 도구가 LLM 컨텍스트에 별도로 주입하고 싶은 멀티모달 블록.
 *
 * openclaude 의 {@code newMessages}(isMeta:true user 메시지) 대응. 도구가 tool_result
 * 텍스트만으로 표현할 수 없는 콘텐츠(PDF document block, 페이지 이미지 등)를 LLM 에게
 * 직접 보여줘야 할 때 사용한다.
 *
 * <p>tool-sdk-core 는 platform-core 의 {@code UnifiedMessage}/{@code ContentBlock} 을
 * 의존하지 않으므로(의존성 방향 보존), 이 경량 record 로 표현하고 platform-core 의
 * 도구 루프(ToolCallHandler)가 로딩 시점에 UnifiedMessage 로 변환한다.
 *
 * @param role      메시지 역할 — 현재는 "user" 만 사용 (openclaude isMeta user 메시지)
 * @param type      블록 종류 — "document" | "image" | "text"
 * @param mediaType MIME 타입 — document="application/pdf", image="image/jpeg" 등 (text 면 null)
 * @param data      base64 인코딩 데이터 (document/image), 또는 text 본문 (type="text")
 * @param filename  원본 파일명 힌트 (nullable)
 */
public record ToolMessageBlock(
        String role,
        String type,
        String mediaType,
        String data,
        String filename
) {
    public static final String ROLE_USER = "user";
    public static final String TYPE_DOCUMENT = "document";
    public static final String TYPE_IMAGE = "image";
    public static final String TYPE_TEXT = "text";

    /** base64 PDF document block (user 역할). */
    public static ToolMessageBlock document(String mediaType, String base64, String filename) {
        return new ToolMessageBlock(ROLE_USER, TYPE_DOCUMENT, mediaType, base64, filename);
    }

    /** base64 이미지 블록 (user 역할). */
    public static ToolMessageBlock image(String mediaType, String base64) {
        return new ToolMessageBlock(ROLE_USER, TYPE_IMAGE, mediaType, base64, null);
    }

    /** 텍스트 블록 (user 역할). */
    public static ToolMessageBlock text(String content) {
        return new ToolMessageBlock(ROLE_USER, TYPE_TEXT, null, content, null);
    }
}
