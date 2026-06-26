package com.platform.workflow.step;

import com.platform.agent.AgentOrchestrator;
import com.platform.agent.SubagentRequest;
import com.platform.agent.SubagentResult;
import com.platform.workflow.StepContext;
import com.platform.workflow.model.WorkflowStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * CR-106: 장기 AGENT_CALL retry 멱등화 단위 테스트.
 *
 * <p>검증:
 * <ul>
 *   <li>직전 attempt 가 turn timeout 류였으면 → resumeSessionId = (run, step) 결정적 키 (Worker 재사용 + --resume)</li>
 *   <li>직전 실패가 timeout 류가 아니면 → resumeSessionId = null (새 세션, 깨끗이 재시도)</li>
 *   <li>첫 시도(이전 실패 없음) → resumeSessionId = null</li>
 *   <li>같은 (run, step) 은 항상 같은 결정적 키 (멱등)</li>
 *   <li>순수 함수 {@code isTurnTimeoutFailure} / {@code deterministicSessionId}</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AgentCallStepExecutorCr106Test {

    @Mock private AgentOrchestrator orchestrator;

    private AgentCallStepExecutor executor() {
        return new AgentCallStepExecutor(orchestrator);
    }

    private WorkflowStep agentStep(String id) {
        return new WorkflowStep(id, "agent step",
                WorkflowStep.StepType.AGENT_CALL,
                Map.of("description", "테스트 에이전트", "prompt", "긴 작업 수행"),
                List.of(), null, null, null);
    }

    private SubagentResult okResult() {
        return SubagentResult.completed("rid", "child-sess", "결과", null,
                null, 0L, OffsetDateTime.now(), null, null);
    }

    private SubagentRequest captureRequest(StepContext context) {
        when(orchestrator.runSingle(any())).thenReturn(okResult());
        executor().execute(agentStep("step-A"), context);
        ArgumentCaptor<SubagentRequest> captor = ArgumentCaptor.forClass(SubagentRequest.class);
        org.mockito.Mockito.verify(orchestrator).runSingle(captor.capture());
        return captor.getValue();
    }

    @Test
    void firstAttempt_noResume() {
        StepContext ctx = new StepContext("run-1", "wf-1", "sess-1", Map.of(), Map.of());
        SubagentRequest req = captureRequest(ctx);
        assertThat(req.resumeSessionId()).isNull();
    }

    @Test
    void retryAfterTimeout_resumesDeterministicSession() {
        StepContext ctx = new StepContext("run-1", "wf-1", "sess-1", Map.of(), Map.of())
                .withRetryFailure("AGENT_CALL failed: turn timeout after 300s");
        SubagentRequest req = captureRequest(ctx);

        String expected = AgentCallStepExecutor.deterministicSessionId("run-1", "step-A");
        assertThat(req.resumeSessionId()).isEqualTo(expected);
        assertThat(req.resumeSessionId()).startsWith("subagent-");
    }

    @Test
    void retryAfterNonTimeout_noResume() {
        StepContext ctx = new StepContext("run-1", "wf-1", "sess-1", Map.of(), Map.of())
                .withRetryFailure("AGENT_CALL failed: No such tool: bash");
        SubagentRequest req = captureRequest(ctx);
        assertThat(req.resumeSessionId()).isNull();
    }

    @Test
    void deterministicSessionId_isStableForSameRunAndStep() {
        String a = AgentCallStepExecutor.deterministicSessionId("run-1", "step-A");
        String b = AgentCallStepExecutor.deterministicSessionId("run-1", "step-A");
        assertThat(a).isEqualTo(b);
    }

    @Test
    void deterministicSessionId_differsByStepOrRun() {
        String base = AgentCallStepExecutor.deterministicSessionId("run-1", "step-A");
        assertThat(AgentCallStepExecutor.deterministicSessionId("run-1", "step-B")).isNotEqualTo(base);
        assertThat(AgentCallStepExecutor.deterministicSessionId("run-2", "step-A")).isNotEqualTo(base);
    }

    @Test
    void isTurnTimeoutFailure_matchesTimeoutVariants() {
        assertThat(AgentCallStepExecutor.isTurnTimeoutFailure("turn timeout after 300s")).isTrue();
        assertThat(AgentCallStepExecutor.isTurnTimeoutFailure("Request timed out")).isTrue();
        assertThat(AgentCallStepExecutor.isTurnTimeoutFailure("AGENT_CALL failed: TURN TIMEOUT")).isTrue();
        assertThat(AgentCallStepExecutor.isTurnTimeoutFailure(null)).isFalse();
        assertThat(AgentCallStepExecutor.isTurnTimeoutFailure("No such tool")).isFalse();
    }

    // CR-117: 32MB(too large) 실패 판정 — 재시도 시 "더 잘게 읽어라" 힌트 주입 트리거.
    @Test
    void isPayloadTooLargeFailure_matchesTooLargeVariants() {
        assertThat(AgentCallStepExecutor.isPayloadTooLargeFailure(
                "AGENT_CALL failed: Request too large (max 32MB). Try with a smaller file.")).isTrue();
        assertThat(AgentCallStepExecutor.isPayloadTooLargeFailure("request_too_large")).isTrue();
        assertThat(AgentCallStepExecutor.isPayloadTooLargeFailure("exceeds 32MB")).isTrue();
        assertThat(AgentCallStepExecutor.isPayloadTooLargeFailure(null)).isFalse();
        assertThat(AgentCallStepExecutor.isPayloadTooLargeFailure("turn timeout")).isFalse();
    }
}
