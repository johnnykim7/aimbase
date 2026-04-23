package com.platform.orchestrator.stream;

import com.platform.llm.model.TokenUsage;

import java.util.List;
import java.util.Map;

/**
 * CR-045 Phase 2-B: 도구 루프 스트리밍 통합 이벤트.
 * ToolCallHandler.executeLoop(..., streamSink)가 발행하는 이벤트. ChatController가
 * SSE 이벤트 5종(delta/thinking/tool_use_start/tool_result/done)으로 매핑.
 */
public sealed interface StreamEvent {

    /** 텍스트 델타 (assistant content). */
    record TextDelta(String delta) implements StreamEvent {}

    /** Extended Thinking 델타. */
    record ThinkingDelta(String delta) implements StreamEvent {}

    /** 도구 호출 시작 (LLM이 TOOL_USE로 턴을 종료 → 실제 도구 실행 직전). */
    record ToolUseStart(String id, String name, Map<String, Object> input) implements StreamEvent {}

    /** 도구 실행 완료. output은 CR-031 축약 적용 후. */
    record ToolResultEvent(String toolUseId, String output, boolean isError) implements StreamEvent {}

    /**
     * 최종 완료 (usage 포함, null 가능).
     * CR-058: citations + ragUsed 확장 — 위젯이 SSE done 이벤트 payload 로 렌더.
     */
    record Done(TokenUsage usage,
                List<Map<String, Object>> citations,
                Boolean ragUsed) implements StreamEvent {
        /** 하위 호환 생성자: citations 없이 사용. */
        public Done(TokenUsage usage) {
            this(usage, null, null);
        }
    }
}
