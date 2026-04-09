package com.platform.llm.model;

public record LLMStreamChunk(
        String id,
        String model,
        String delta,
        boolean done,
        TokenUsage usage,
        String type       // CR-030: "text" | "thinking" | null(done/error)
) {
    /** 하위 호환: 5-파라미터 생성자 */
    public LLMStreamChunk(String id, String model, String delta, boolean done, TokenUsage usage) {
        this(id, model, delta, done, usage, delta != null ? "text" : null);
    }

    public static LLMStreamChunk text(String id, String model, String delta) {
        return new LLMStreamChunk(id, model, delta, false, null, "text");
    }

    /** CR-030: Extended Thinking 스트리밍 청크 */
    public static LLMStreamChunk thinking(String id, String model, String delta) {
        return new LLMStreamChunk(id, model, delta, false, null, "thinking");
    }

    public static LLMStreamChunk done(String id, String model, TokenUsage usage) {
        return new LLMStreamChunk(id, model, null, true, usage, null);
    }
}
