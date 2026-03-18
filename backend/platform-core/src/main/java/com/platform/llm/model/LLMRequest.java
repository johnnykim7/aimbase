package com.platform.llm.model;

import java.util.List;
import java.util.Map;

/**
 * LLM 호출 요청 모델.
 *
 * @param toolChoice     도구 선택 전략 (null 또는 "auto": LLM 자율 선택,
 *                       "none": 도구 미사용, "required": 반드시 도구 사용,
 *                       그 외: 특정 도구명 강제)
 * @param responseSchema 구조화된 출력용 JSON Schema (CR-007). null이면 일반 텍스트 응답.
 *                       어댑터별로 다르게 처리됨: OpenAI(response_format), Claude(prompt+tool trick), Ollama(format+prompt).
 */
public record LLMRequest(
        String model,
        List<UnifiedMessage> messages,
        List<UnifiedToolDef> tools,
        ModelConfig config,
        boolean stream,
        String sessionId,
        String toolChoice,
        Map<String, Object> responseSchema
) {
    public LLMRequest(String model, List<UnifiedMessage> messages) {
        this(model, messages, null, ModelConfig.defaults(), false, null, null, null);
    }

    /** toolChoice 없는 기존 호환용 생성자 */
    public LLMRequest(String model, List<UnifiedMessage> messages, List<UnifiedToolDef> tools,
                      ModelConfig config, boolean stream, String sessionId) {
        this(model, messages, tools, config, stream, sessionId, null, null);
    }

    /** responseSchema 없는 기존 호환용 생성자 */
    public LLMRequest(String model, List<UnifiedMessage> messages, List<UnifiedToolDef> tools,
                      ModelConfig config, boolean stream, String sessionId, String toolChoice) {
        this(model, messages, tools, config, stream, sessionId, toolChoice, null);
    }
}
