package com.platform.orchestrator.stream;

import com.platform.llm.model.LLMStreamChunk;
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
     * CR-175: CLI 연결은 도구를 실행기 안에서 써서 서버 도구 루프를 타지 않는다. 실행기가 알려 온 도구 사용을
     * API 연결과 같은 도구 이벤트로 바꿔 위젯 도구 접은 줄에 보이게 한다(PRD-374). SSE 가 null 값을 못 실어
     * 빈 id 와 이름은 빈 글자로 둔다. 관찰 청크가 아니면 null.
     */
    static StreamEvent ofObservedTool(LLMStreamChunk chunk) {
        LLMStreamChunk.ObservedTool ot = chunk.observedTool();
        if (ot == null) return null;
        String id = ot.toolUseId() != null ? ot.toolUseId() : "";
        if ("tool_result".equals(chunk.type())) {
            return new ToolResultEvent(id, ot.output(), false);
        }
        return new ToolUseStart(id, ot.toolName() != null ? ot.toolName() : "", ot.input());
    }

    /**
     * 최종 완료 (usage 포함, null 가능).
     * CR-058: citations + ragUsed 확장 — 위젯이 SSE done 이벤트 payload 로 렌더.
     * CR-175: turnId, turnSeq, model 확장. 대화 기록을 저장한 뒤에 채워 내보낸다(PRD-369).
     */
    record Done(TokenUsage usage,
                List<Map<String, Object>> citations,
                Boolean ragUsed,
                String turnId,
                Integer turnSeq,
                String model) implements StreamEvent {
        /** 하위 호환 생성자: citations 없이 사용. */
        public Done(TokenUsage usage) {
            this(usage, null, null, null, null, null);
        }

        public Done(TokenUsage usage, List<Map<String, Object>> citations, Boolean ragUsed) {
            this(usage, citations, ragUsed, null, null, null);
        }
    }

    /**
     * CR-053 Phase 2: 서브에이전트 실행 시작.
     * @param runId       서브에이전트 실행 ID
     * @param agentType   GENERAL / PLAN / EXPLORE / GUIDE / VERIFICATION
     * @param description 부모가 지정한 작업 설명
     */
    record SubagentStart(String runId, String agentType, String description) implements StreamEvent {}

    /**
     * CR-053 Phase 2: 서브에이전트 실행 종료.
     * @param runId       서브에이전트 실행 ID
     * @param status      COMPLETED / FAILED / TIMEOUT / CANCELLED / RUNNING(백그라운드 즉시 반환)
     * @param summary     결과 요약 (COMPLETED 시 첫 200자, 실패 시 에러 메시지)
     * @param durationMs  실행 시간
     */
    record SubagentDone(String runId, String status, String summary, long durationMs) implements StreamEvent {}
}
