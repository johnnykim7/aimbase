package com.platform.runtime;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * CR-029: LLM API 기반 런타임 어댑터.
 * 기존 LLMAdapter(Anthropic/OpenAI/Ollama)를 RuntimeAdapter로 래핑.
 * 정제된 context 기반 구조화 응답, schema-first output에 적합.
 */
@Component
public class LlmApiRuntimeAdapter implements RuntimeAdapter {

    @Override
    public String getRuntimeId() {
        return "llm_api";
    }

    @Override
    public RuntimeCapabilityProfile getCapabilities() {
        return new RuntimeCapabilityProfile(
                true,   // streaming
                true,   // toolUse
                true,   // multiTurn
                true,   // longContext (200k)
                false,  // autonomousExploration
                true,   // structuredOutput
                200_000,
                List.of("claude-sonnet-4-20250514", "claude-haiku-4-5-20251001", "gpt-4o"),
                List.of("structured_output", "schema_first", "short_task")
        );
    }

    @Override
    public RuntimeResult execute(RuntimeRequest request) {
        // 실제 구현은 OrchestratorEngine의 기존 LLM 호출 경로를 위임
        // 현재는 인터페이스 + 능력 프로필만 정의
        throw new UnsupportedOperationException(
                "LlmApiRuntimeAdapter.execute()는 OrchestratorEngine에서 직접 호출합니다.");
    }
}
