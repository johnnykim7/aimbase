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

    /**
     * CR-185 PRD-560: 도구 실행 전 승인을 묻기 시작. 위젯 본체는 이름을 모르고 틀의 streamEvent 로 승인 기능에 넘긴다.
     * message 는 정책 안내 문구이고 없으면 null 이다. expiresAt 은 시간대를 포함한 ISO-8601.
     */
    record ApprovalRequired(String approvalId, String toolUseId, String toolName, String description,
                            Map<String, Object> input, String message, String expiresAt,
                            int timeoutSeconds) implements StreamEvent {}

    /** CR-185 PRD-560: 승인 요청이 정해짐. status 는 approved, rejected, timeout, cancelled. */
    record ApprovalResolved(String approvalId, String status, String decidedAt) implements StreamEvent {}

    /**
     * CR-185: 승인을 기다리는 동안 스트림이 살아 있게 하는 주석 줄. 보내지 못하면 받는 쪽이 lost 를 세운다.
     * 승인 관문은 그것으로 연결이 끊긴 것을 안다.
     */
    record KeepAlive(java.util.concurrent.atomic.AtomicBoolean lost) implements StreamEvent {}
}
