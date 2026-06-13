package com.platform.llm.model;

import java.util.Map;

/**
 * CR-102: CLI 어댑터가 한 번의 호출 안에서 자율로 돈 내부 도구 루프의 "관찰" 기록.
 *
 * <p>CLI(stream-json)의 tool_use/tool_result 이벤트를 Worker 가 페어링해 담는다 —
 * 도구 루프 재진입용이 아니라 가시화 전용(워크플로우 run 타임라인 적재).
 * {@link LLMResponse#toolCalls()} 와 분리한 이유: toolCalls 에 실으면 OrchestratorEngine 이
 * tool_result 를 주입하려 해 CLI 내부 완결 루프와 충돌한다 (CR-050 Phase 9).
 *
 * @param toolName   도구 이름 (예: mcp__aimbase-server__web_search)
 * @param input      도구 input 전문 (CLI 가 보낸 그대로)
 * @param output     도구 결과 본문 (tool_result 미수신 시 null)
 * @param durationMs tool_use 관찰 → tool_result 관찰 사이 시간 (근사값, 미페어링 시 null)
 */
public record ObservedToolEvent(
        String toolName,
        Map<String, Object> input,
        String output,
        Long durationMs
) {}
