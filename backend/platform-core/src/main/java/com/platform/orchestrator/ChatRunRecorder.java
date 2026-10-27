package com.platform.orchestrator;

import com.platform.domain.WorkflowRunEntity;
import com.platform.repository.WorkflowRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * CR-130: 채팅(chat/completions) 경로도 "실행 내역"에서 보이게 하는 run 기록자.
 *
 * <p><b>왜 필요한가</b>: CLI 어댑터는 도구 루프를 자기 프로세스에서 완결하고 그 관찰
 * ({@code observed_tool_events})을 BE 로 올려보내지만, 적재 조건이
 * {@code canRecord = eventRecorder != null && runId != null} 이라 <b>workflowRunId 가 없는 채팅은
 * 관찰이 통째로 버려졌다</b>. 그 결과 CLI 가 실제로 도구 8개를 호출해도 응답 본문에도, DB 에도
 * 궤적이 남지 않아 "무엇을 보고 답했는지" 추적이 불가능했다(운영 실측).
 *
 * <p><b>방식</b>: 채팅 요청 시 {@code workflow_id = NULL} 인 {@link WorkflowRunEntity} 를 만들어
 * 그 runId 를 도구 루프까지 흘린다. 그러면 <b>기존 워크플로우 이벤트 적재 로직이 그대로 동작</b>하고
 * FE "실행 내역" 메뉴(동일 LNB)에서 워크플로우와 같은 화면으로 열람된다.
 * {@code workflow_id} 는 nullable 이고 FK 도 NULL 이면 제약을 타지 않으므로 스키마 변경이 없다.
 *
 * <p><b>적용 범위</b>: CLI 커넥션 채팅만. 일반 LLM 채팅은 내부 도구 궤적이 없어 남길 것이 없다.
 */
@Component
public class ChatRunRecorder {

    private static final Logger log = LoggerFactory.getLogger(ChatRunRecorder.class);

    /** 채팅 run 식별용 — workflow_id 가 NULL 인 run 이 채팅이다. FE 뱃지 판정도 이 규칙을 쓴다. */
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    private final WorkflowRunRepository workflowRunRepository;
    private final com.platform.workflow.event.WorkflowRunEventRecorder eventRecorder;

    public ChatRunRecorder(WorkflowRunRepository workflowRunRepository,
                           com.platform.workflow.event.WorkflowRunEventRecorder eventRecorder) {
        this.workflowRunRepository = workflowRunRepository;
        this.eventRecorder = eventRecorder;
    }

    /**
     * CR-130: 도구 없는 단발 채팅 경로({@code actionsEnabled=false})의 CLI 도구 관찰을 적재한다.
     *
     * <p>이 경로는 {@code ToolCallHandler.executeLoop} 를 타지 않고 {@code adapter.chat()} 을 직접
     * 호출하므로, CLI 가 응답에 실어 보낸 {@code observed_tool_events} 를 아무도 소비하지 않았다
     * (운영 실측: run 은 생기는데 events 0건). 여기서 명시적으로 적재해야 "실행 내역" 상세에
     * TOOL_USE/TOOL_RESULT 가 워크플로우와 동일하게 나타난다.
     *
     * <p>기록 실패가 채팅 응답을 막지 않도록 예외는 삼킨다.
     */
    public void recordObservedTools(UUID runId, com.platform.llm.model.LLMResponse response) {
        if (runId == null || response == null || !response.hasObservedToolEvents()) return;
        try {
            eventRecorder.observedTools(runId, null, null, response.observedToolEvents());
        } catch (Exception e) {
            log.warn("채팅 run 도구 관찰 적재 실패 (무시): runId={}, {}", runId, e.getMessage());
        }
    }

    /**
     * 채팅 run 시작. 실패해도 채팅 자체는 계속되어야 하므로 예외를 삼키고 null 을 반환한다
     * (기록은 부가 기능 — 본 기능을 막지 않는다).
     *
     * @return 생성된 runId. 실패 시 null
     */
    public UUID start(String sessionId, String connectionId, String model) {
        try {
            WorkflowRunEntity run = new WorkflowRunEntity();
            run.setWorkflowId(null);           // ★ NULL = 채팅 run
            run.setSessionId(sessionId);
            run.setStatus(STATUS_RUNNING);
            run.setStartedAt(OffsetDateTime.now());
            run.setInputData(Map.of(
                    "source", "chat",
                    "connection_id", connectionId != null ? connectionId : "",
                    "model", model != null ? model : ""));
            WorkflowRunEntity saved = workflowRunRepository.save(run);
            return saved.getId();
        } catch (Exception e) {
            log.warn("채팅 run 생성 실패 (기록만 생략, 채팅은 계속): session={}, {}", sessionId, e.getMessage());
            return null;
        }
    }

    /** 채팅 run 정상 종료. runId 가 null 이면 no-op. */
    public void complete(UUID runId) {
        finish(runId, STATUS_COMPLETED, null);
    }

    /** 채팅 run 실패 종료. runId 가 null 이면 no-op. */
    public void fail(UUID runId, String errorMessage) {
        finish(runId, STATUS_FAILED, errorMessage);
    }

    private void finish(UUID runId, String status, String errorMessage) {
        if (runId == null) return;
        try {
            workflowRunRepository.findById(runId).ifPresent(run -> {
                run.setStatus(status);
                run.setCompletedAt(OffsetDateTime.now());
                if (errorMessage != null && !errorMessage.isBlank()) {
                    run.setError(Map.of("message", errorMessage));
                }
                workflowRunRepository.save(run);
            });
        } catch (Exception e) {
            log.warn("채팅 run 종료 처리 실패 (무시): runId={}, {}", runId, e.getMessage());
        }
    }
}
