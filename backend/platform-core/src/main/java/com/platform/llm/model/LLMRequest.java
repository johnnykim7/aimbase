package com.platform.llm.model;

import com.platform.tool.model.UnifiedToolDef;
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
 * @param workingDirectory CLI 경로(ClaudeCliAdapter→Runner→Worker) 전용. 워크플로우 run 격리 workspace 절대경로.
 *                       Worker 가 CLI 프로세스 cwd(pb.directory)로 설정 → HYBRID 모드의 CLI 내장 Read/Bash 가
 *                       상대경로(attachments/...)로도 작업장을 정확히 읽는다. null 이면 기존 동작(cwd 미설정).
 */
public record LLMRequest(
        String model,
        List<UnifiedMessage> messages,
        List<UnifiedToolDef> tools,
        ModelConfig config,
        boolean stream,
        String sessionId,
        String toolChoice,
        Map<String, Object> responseSchema,
        String workingDirectory
) {
    public LLMRequest(String model, List<UnifiedMessage> messages) {
        this(model, messages, null, ModelConfig.defaults(), false, null, null, null, null);
    }

    /** toolChoice 없는 기존 호환용 생성자 */
    public LLMRequest(String model, List<UnifiedMessage> messages, List<UnifiedToolDef> tools,
                      ModelConfig config, boolean stream, String sessionId) {
        this(model, messages, tools, config, stream, sessionId, null, null, null);
    }

    /** responseSchema 없는 기존 호환용 생성자 */
    public LLMRequest(String model, List<UnifiedMessage> messages, List<UnifiedToolDef> tools,
                      ModelConfig config, boolean stream, String sessionId, String toolChoice) {
        this(model, messages, tools, config, stream, sessionId, toolChoice, null, null);
    }

    /** workingDirectory 없는 기존 호환용 생성자 (responseSchema 까지) */
    public LLMRequest(String model, List<UnifiedMessage> messages, List<UnifiedToolDef> tools,
                      ModelConfig config, boolean stream, String sessionId, String toolChoice,
                      Map<String, Object> responseSchema) {
        this(model, messages, tools, config, stream, sessionId, toolChoice, responseSchema, null);
    }

    /** 모델만 교체한 복사본 반환 (Fallback Chain용) */
    public LLMRequest withModel(String newModel) {
        return new LLMRequest(newModel, messages, tools, config, stream, sessionId, toolChoice, responseSchema, workingDirectory);
    }

    /** workingDirectory 만 교체한 복사본 (CLI cwd 전파용) */
    public LLMRequest withWorkingDirectory(String dir) {
        return new LLMRequest(model, messages, tools, config, stream, sessionId, toolChoice, responseSchema, dir);
    }

    /** CR-117: messages 만 교체한 복사본 (CLI 어댑터의 시스템 프롬프트 정리용) */
    public LLMRequest withMessages(List<UnifiedMessage> newMessages) {
        return new LLMRequest(model, newMessages, tools, config, stream, sessionId, toolChoice, responseSchema, workingDirectory);
    }
}
