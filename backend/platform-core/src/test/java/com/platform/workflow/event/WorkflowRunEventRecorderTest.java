package com.platform.workflow.event;

import com.platform.domain.WorkflowRunEventEntity;
import com.platform.domain.WorkflowRunEventEntity.EventType;
import com.platform.repository.WorkflowRunEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * CR-090 WorkflowRunEventRecorder 단위 테스트.
 *
 * <p>Recorder 는 VT 비동기 fire-and-forget 이라 await 없이는 결과 확인이 어렵다.
 * 여기서는 ArgumentCaptor 와 짧은 polling 으로 INSERT 가 발생했음을 확인한다.
 */
@DisplayName("WorkflowRunEventRecorder — CR-090")
class WorkflowRunEventRecorderTest {

    private WorkflowRunEventRepository repository;
    private WorkflowRunEventRecorder recorder;

    @BeforeEach
    void setUp() {
        repository = mock(WorkflowRunEventRepository.class);
        recorder = new WorkflowRunEventRecorder(repository);
    }

    @Test
    @DisplayName("stepStart → STEP_START 이벤트 저장, payload.step_type 포함")
    void stepStart() throws Exception {
        UUID runId = UUID.randomUUID();

        recorder.stepStart(runId, "step1", "LLM_CALL");

        WorkflowRunEventEntity saved = waitForSave();
        assertThat(saved.getRunId()).isEqualTo(runId);
        assertThat(saved.getStepId()).isEqualTo("step1");
        assertThat(saved.getEventType()).isEqualTo(EventType.STEP_START);
        assertThat(saved.getPayload()).containsEntry("step_type", "LLM_CALL");
        assertThat(saved.getDurationMs()).isNull();
    }

    @Test
    @DisplayName("stepEnd → STEP_END + duration_ms + output_size payload")
    void stepEnd() throws Exception {
        UUID runId = UUID.randomUUID();

        recorder.stepEnd(runId, "step1", 1234L, 42);

        WorkflowRunEventEntity saved = waitForSave();
        assertThat(saved.getEventType()).isEqualTo(EventType.STEP_END);
        assertThat(saved.getDurationMs()).isEqualTo(1234L);
        assertThat(saved.getPayload()).containsEntry("output_size", 42);
    }

    @Test
    @DisplayName("stepFailed → STEP_FAILED + error truncated + attempts")
    void stepFailed() throws Exception {
        UUID runId = UUID.randomUUID();

        recorder.stepFailed(runId, "step1", 999L, "boom", 3);

        WorkflowRunEventEntity saved = waitForSave();
        assertThat(saved.getEventType()).isEqualTo(EventType.STEP_FAILED);
        assertThat(saved.getDurationMs()).isEqualTo(999L);
        assertThat(saved.getPayload()).containsEntry("error", "boom");
        assertThat(saved.getPayload()).containsEntry("attempts", 3);
    }

    @Test
    @DisplayName("toolUse — input_keys, input_preview ≤100자")
    void toolUse() throws Exception {
        UUID runId = UUID.randomUUID();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("query", "x".repeat(500));
        input.put("flag", true);

        recorder.toolUse(runId, "step1", 0, "web_search", input, null);

        WorkflowRunEventEntity saved = waitForSave();
        assertThat(saved.getEventType()).isEqualTo(EventType.TOOL_USE);
        assertThat(saved.getToolName()).isEqualTo("web_search");
        assertThat(saved.getIteration()).isEqualTo(0);
        assertThat(saved.getPayload()).containsKey("input_keys");
        String preview = (String) saved.getPayload().get("input_preview");
        // 100자 + "…" — 정확히 101 chars (단, truncate 안 일어났으면 적을 수도)
        assertThat(preview.length()).isLessThanOrEqualTo(101);
    }

    @Test
    @DisplayName("toolResult — ok=true 일 때 error 없음, output_size 채워짐")
    void toolResultSuccess() throws Exception {
        UUID runId = UUID.randomUUID();

        recorder.toolResult(runId, "step1", null, "calc", 50L, true, null, 128, null);

        WorkflowRunEventEntity saved = waitForSave();
        assertThat(saved.getEventType()).isEqualTo(EventType.TOOL_RESULT);
        assertThat(saved.getDurationMs()).isEqualTo(50L);
        assertThat(saved.getPayload()).containsEntry("ok", true);
        assertThat(saved.getPayload()).containsEntry("output_size", 128);
        assertThat(saved.getPayload()).doesNotContainKey("error");
    }

    @Test
    @DisplayName("toolResult — ok=false + error 메시지 truncate")
    void toolResultFailure() throws Exception {
        UUID runId = UUID.randomUUID();
        String longErr = "e".repeat(300);

        recorder.toolResult(runId, "step1", null, "calc", 10L, false, longErr, 0, null);

        WorkflowRunEventEntity saved = waitForSave();
        assertThat(saved.getEventType()).isEqualTo(EventType.TOOL_RESULT);
        assertThat(saved.getPayload()).containsEntry("ok", false);
        String err = (String) saved.getPayload().get("error");
        assertThat(err.length()).isLessThanOrEqualTo(201);  // 200 + "…"
    }

    @Test
    @DisplayName("llmResponse — model, in_tok, out_tok, finish_reason 채움")
    void llmResponse() throws Exception {
        UUID runId = UUID.randomUUID();

        recorder.llmResponse(runId, "step1", null,
                "claude-sonnet-4-5", 1000, 250, "END", 800L, null, null);

        WorkflowRunEventEntity saved = waitForSave();
        assertThat(saved.getEventType()).isEqualTo(EventType.LLM_RESPONSE);
        assertThat(saved.getDurationMs()).isEqualTo(800L);
        assertThat(saved.getPayload()).containsEntry("model", "claude-sonnet-4-5");
        assertThat(saved.getPayload()).containsEntry("in_tok", 1000);
        assertThat(saved.getPayload()).containsEntry("out_tok", 250);
        assertThat(saved.getPayload()).containsEntry("finish_reason", "END");
    }

    // ─── CR-102: 본문 전문 적재 오버로드 ───

    @Test
    @DisplayName("CR-102: stepEnd(outputBody) → output_text 전문 적재 (절단 없음)")
    void stepEndWithBody() throws Exception {
        UUID runId = UUID.randomUUID();
        String body = "결과 본문 ".repeat(100); // PREVIEW_MAX(100자) 초과

        recorder.stepEnd(runId, "step1", 1234L, body.length(), body);

        WorkflowRunEventEntity saved = waitForSave();
        assertThat(saved.getEventType()).isEqualTo(EventType.STEP_END);
        assertThat(saved.getOutputText()).isEqualTo(body); // 절단 없이 전문 그대로
    }

    @Test
    @DisplayName("CR-102: toolUse → input_json 전문 적재 + 메타(preview)는 기존 유지")
    void toolUseStoresFullInput() throws Exception {
        UUID runId = UUID.randomUUID();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("query", "q".repeat(500));

        recorder.toolUse(runId, "step1", null, "web_search", input, null);

        WorkflowRunEventEntity saved = waitForSave();
        assertThat(saved.getInputJson()).isEqualTo(input); // 전문 (Map 그대로)
        assertThat(saved.getPayload()).containsKey("input_preview"); // 메타 병행 유지
    }

    @Test
    @DisplayName("CR-102: toolResult(outputBody) → output_text 전문 적재")
    void toolResultWithBody() throws Exception {
        UUID runId = UUID.randomUUID();
        String body = "o".repeat(5000);

        recorder.toolResult(runId, "step1", null, "calc", 50L, true, null, body.length(), null, body);

        WorkflowRunEventEntity saved = waitForSave();
        assertThat(saved.getOutputText()).isEqualTo(body);
        assertThat(saved.getPayload()).containsEntry("output_size", 5000);
    }

    @Test
    @DisplayName("CR-102: llmResponse(promptBody, responseBody) → prompt_text/response_text 전문 적재")
    void llmResponseWithBodies() throws Exception {
        UUID runId = UUID.randomUUID();
        String promptBody = "[SYSTEM]\nsys\n\n[PROMPT]\n" + "p".repeat(2000);
        String responseBody = "r".repeat(3000);

        recorder.llmResponse(runId, "step1", null,
                "claude-sonnet-4-5", 1000, 250, "END", 800L, null, null,
                promptBody, responseBody);

        WorkflowRunEventEntity saved = waitForSave();
        assertThat(saved.getPromptText()).isEqualTo(promptBody);
        assertThat(saved.getResponseText()).isEqualTo(responseBody);
        assertThat(saved.getPayload()).containsEntry("model", "claude-sonnet-4-5");
    }

    @Test
    @DisplayName("CR-102: 기존 시그니처(본문 미전달) → 본문 컬럼 null 유지 (하위호환)")
    void legacySignatureLeavesBodiesNull() throws Exception {
        UUID runId = UUID.randomUUID();

        recorder.llmResponse(runId, "step1", null,
                "claude-sonnet-4-5", 1000, 250, "END", 800L, null, null);

        WorkflowRunEventEntity saved = waitForSave();
        assertThat(saved.getPromptText()).isNull();
        assertThat(saved.getResponseText()).isNull();
        assertThat(saved.getOutputText()).isNull();
    }

    @Test
    @DisplayName("CR-102: observedTools — 페어링된 관찰은 TOOL_USE+TOOL_RESULT, 미페어링은 TOOL_USE 만")
    void observedToolsBatch() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID subRunId = UUID.randomUUID();
        var paired = new com.platform.llm.model.ObservedToolEvent(
                "web_search", Map.of("query", "aimbase"), "검색 결과 전문", 1234L);
        var unpaired = new com.platform.llm.model.ObservedToolEvent(
                "bash", Map.of("command", "ls"), null, null);

        recorder.observedTools(runId, "step1", subRunId, java.util.List.of(paired, unpaired));

        // paired: TOOL_USE + TOOL_RESULT / unpaired: TOOL_USE 만 → 총 3건
        ArgumentCaptor<WorkflowRunEventEntity> captor =
                ArgumentCaptor.forClass(WorkflowRunEventEntity.class);
        verify(repository, timeout(1000).times(3)).save(captor.capture());

        var events = captor.getAllValues();
        var toolUses = events.stream().filter(e -> e.getEventType() == EventType.TOOL_USE).toList();
        var toolResults = events.stream().filter(e -> e.getEventType() == EventType.TOOL_RESULT).toList();
        assertThat(toolUses).hasSize(2);
        assertThat(toolResults).hasSize(1);

        var searchUse = toolUses.stream().filter(e -> "web_search".equals(e.getToolName())).findFirst().orElseThrow();
        assertThat(searchUse.getInputJson()).containsEntry("query", "aimbase");
        assertThat(searchUse.getIteration()).isEqualTo(0);
        assertThat(searchUse.getSubagentRunId()).isEqualTo(subRunId);

        var searchResult = toolResults.get(0);
        assertThat(searchResult.getToolName()).isEqualTo("web_search");
        assertThat(searchResult.getOutputText()).isEqualTo("검색 결과 전문");
        assertThat(searchResult.getDurationMs()).isEqualTo(1234L);

        var bashUse = toolUses.stream().filter(e -> "bash".equals(e.getToolName())).findFirst().orElseThrow();
        assertThat(bashUse.getIteration()).isEqualTo(1);
    }

    @Test
    @DisplayName("runId == null → publish 스킵 (워크플로우 무관 호출 방어)")
    void nullRunIdSkipped() throws Exception {
        recorder.stepStart(null, "step1", "TOOL_CALL");
        Thread.sleep(50);   // VT 가 안 돌아도 충분
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Repository 예외 발생해도 호출자에 전파 안 됨 (워크플로우 hot path 보호)")
    void exceptionSwallowed() throws Exception {
        when(repository.save(any())).thenThrow(new RuntimeException("DB down"));
        UUID runId = UUID.randomUUID();

        // 호출 자체가 예외 안 던지면 OK
        recorder.stepStart(runId, "step1", "LLM_CALL");

        // VT 가 save 호출까지 도달했음을 확인
        Thread.sleep(150);
        verify(repository, atLeastOnce()).save(any());
    }

    // ─── 헬퍼: VT save 가 끝날 때까지 짧게 polling ───

    private WorkflowRunEventEntity waitForSave() throws Exception {
        ArgumentCaptor<WorkflowRunEventEntity> captor =
                ArgumentCaptor.forClass(WorkflowRunEventEntity.class);
        AtomicReference<WorkflowRunEventEntity> result = new AtomicReference<>();
        long deadline = System.currentTimeMillis() + 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                verify(repository, atLeastOnce()).save(captor.capture());
                result.set(captor.getValue());
                return result.get();
            } catch (AssertionError notYet) {
                Thread.sleep(20);
            }
        }
        throw new AssertionError("Repository.save 가 1초 안에 호출되지 않았습니다");
    }
}
