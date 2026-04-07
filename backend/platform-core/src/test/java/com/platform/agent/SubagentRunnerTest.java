package com.platform.agent;

import com.platform.domain.SubagentRunEntity;
import com.platform.hook.HookDispatcher;
import com.platform.hook.HookOutput;
import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.TokenUsage;
import com.platform.orchestrator.ChatResponse;
import com.platform.orchestrator.OrchestratorEngine;
import com.platform.repository.SubagentRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * CR-030 PRD-207: SubagentRunner 단위 테스트.
 */
@ExtendWith(MockitoExtension.class)
class SubagentRunnerTest {

    @Mock private OrchestratorEngine orchestratorEngine;
    @Mock private SubagentRunRepository subagentRunRepository;
    @Mock private WorktreeManager worktreeManager;
    @Mock private HookDispatcher hookDispatcher;
    @Mock private SubagentLifecycleManager lifecycleManager;

    private SubagentRunner runner;

    @BeforeEach
    void setUp() {
        runner = new SubagentRunner(orchestratorEngine, subagentRunRepository,
                worktreeManager, hookDispatcher, lifecycleManager);

        lenient().when(hookDispatcher.dispatch(any(), any()))
                .thenReturn(HookOutput.PASSTHROUGH);
        lenient().when(subagentRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void foreground_success_returnsCompleted() {
        ChatResponse chatResponse = new ChatResponse(
                "resp-1", "claude-sonnet", "child-sess",
                List.of(new ContentBlock.Text("결과 텍스트")),
                List.of(), new TokenUsage(100, 200), 0.01);

        when(orchestratorEngine.chat(any())).thenReturn(chatResponse);

        SubagentRequest request = new SubagentRequest(
                "test agent", "작업을 수행해줘", null, null,
                SubagentRequest.IsolationMode.NONE, false, 30_000,
                Map.of(), "parent-sess-1");

        SubagentResult result = runner.run(request);

        assertThat(result.status()).isEqualTo(SubagentResult.Status.COMPLETED);
        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.output()).contains("결과 텍스트");
        assertThat(result.isSuccess()).isTrue();

        // lifecycle 등록/해제 확인
        verify(lifecycleManager).register(any());
        verify(lifecycleManager).unregister(any());
    }

    @Test
    void foreground_orchestratorThrows_returnsFailed() {
        when(orchestratorEngine.chat(any())).thenThrow(new RuntimeException("LLM error"));

        SubagentRequest request = new SubagentRequest(
                "failing agent", "실패할 작업", null, null,
                SubagentRequest.IsolationMode.NONE, false, 5_000,
                Map.of(), "parent-sess-1");

        SubagentResult result = runner.run(request);

        assertThat(result.status()).isEqualTo(SubagentResult.Status.FAILED);
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.error()).contains("LLM error");
    }

    @Test
    void foreground_withWorktree_createsAndCleansUp() {
        WorktreeContext wCtx = new WorktreeContext("/tmp/wt-test", "subagent/test-branch", "abc123");
        when(worktreeManager.create(any())).thenReturn(wCtx);
        when(worktreeManager.hasChanges(wCtx)).thenReturn(false);

        ChatResponse chatResponse = new ChatResponse(
                "resp-2", "claude-sonnet", "child-sess",
                List.of(new ContentBlock.Text("worktree 결과")),
                List.of(), new TokenUsage(50, 100), 0.005);
        when(orchestratorEngine.chat(any())).thenReturn(chatResponse);

        SubagentRequest request = new SubagentRequest(
                "isolated agent", "격리 작업", null, null,
                SubagentRequest.IsolationMode.WORKTREE, false, 30_000,
                Map.of(), "parent-sess-1");

        SubagentResult result = runner.run(request);

        assertThat(result.status()).isEqualTo(SubagentResult.Status.COMPLETED);
        assertThat(result.worktreePath()).isNull(); // 변경 없으므로 null
        verify(worktreeManager).create(any());
        verify(worktreeManager).remove(wCtx);
    }

    @Test
    void foreground_withWorktreeChanges_preservesPath() {
        WorktreeContext wCtx = new WorktreeContext("/tmp/wt-changed", "subagent/changed", "def456");
        when(worktreeManager.create(any())).thenReturn(wCtx);
        when(worktreeManager.hasChanges(wCtx)).thenReturn(true);

        ChatResponse chatResponse = new ChatResponse(
                "resp-3", "claude-sonnet", "child-sess",
                List.of(new ContentBlock.Text("코드 변경됨")),
                List.of(), new TokenUsage(200, 300), 0.02);
        when(orchestratorEngine.chat(any())).thenReturn(chatResponse);

        SubagentRequest request = new SubagentRequest(
                "code agent", "코드 수정", null, null,
                SubagentRequest.IsolationMode.WORKTREE, false, 30_000,
                Map.of(), "parent-sess-1");

        SubagentResult result = runner.run(request);

        assertThat(result.worktreePath()).isEqualTo("/tmp/wt-changed");
        assertThat(result.branchName()).isEqualTo("subagent/changed");
        verify(worktreeManager, never()).remove(any());
    }

    @Test
    void background_returnsRunningImmediately() {
        // 백그라운드는 즉시 RUNNING 반환 (chat은 백그라운드 스레드에서 호출될 수도 안 될 수도 있음)
        ChatResponse chatResponse = new ChatResponse(
                "resp-4", "claude-sonnet", "child-sess",
                List.of(new ContentBlock.Text("bg result")),
                List.of(), new TokenUsage(10, 20), 0.001);
        lenient().when(orchestratorEngine.chat(any())).thenReturn(chatResponse);

        SubagentRequest request = new SubagentRequest(
                "bg agent", "백그라운드 작업", null, null,
                SubagentRequest.IsolationMode.NONE, true, 60_000,
                Map.of(), "parent-sess-1");

        SubagentResult result = runner.run(request);

        assertThat(result.status()).isEqualTo(SubagentResult.Status.RUNNING);
        assertThat(result.subagentRunId()).isNotNull();
    }

    @Test
    void getStatus_found_returnsResult() {
        UUID runId = UUID.randomUUID();
        SubagentRunEntity entity = new SubagentRunEntity();
        entity.setId(runId);
        entity.setChildSessionId("child-1");
        entity.setStatus("COMPLETED");
        entity.setExitCode(0);
        entity.setOutput("done");
        entity.setInputTokens(100);
        entity.setOutputTokens(200);

        when(subagentRunRepository.findById(runId)).thenReturn(Optional.of(entity));

        SubagentResult result = runner.getStatus(runId.toString());

        assertThat(result.status()).isEqualTo(SubagentResult.Status.COMPLETED);
        assertThat(result.output()).isEqualTo("done");
    }

    @Test
    void getStatus_notFound_throws() {
        UUID runId = UUID.randomUUID();
        when(subagentRunRepository.findById(runId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> runner.getStatus(runId.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void dbEntity_createdCorrectly() {
        ChatResponse chatResponse = new ChatResponse(
                "resp-5", "claude-sonnet", "child-sess",
                List.of(new ContentBlock.Text("entity test")),
                List.of(), new TokenUsage(50, 100), 0.005);
        when(orchestratorEngine.chat(any())).thenReturn(chatResponse);

        SubagentRequest request = new SubagentRequest(
                "db agent", "DB 테스트", "claude-opus", "conn-1",
                SubagentRequest.IsolationMode.NONE, false, 45_000,
                Map.of("key", "val"), "parent-sess-1");

        runner.run(request);

        ArgumentCaptor<SubagentRunEntity> captor = ArgumentCaptor.forClass(SubagentRunEntity.class);
        verify(subagentRunRepository, atLeast(1)).save(captor.capture());

        SubagentRunEntity saved = captor.getAllValues().get(0);
        assertThat(saved.getParentSessionId()).isEqualTo("parent-sess-1");
        assertThat(saved.getDescription()).isEqualTo("db agent");
        assertThat(saved.getIsolationMode()).isEqualTo("NONE");
        assertThat(saved.getTimeoutMs()).isEqualTo(45_000);
    }

    @Test
    void hookEvents_dispatched() {
        ChatResponse chatResponse = new ChatResponse(
                "resp-6", "claude-sonnet", "child-sess",
                List.of(new ContentBlock.Text("hook test")),
                List.of(), new TokenUsage(10, 20), 0.001);
        when(orchestratorEngine.chat(any())).thenReturn(chatResponse);

        SubagentRequest request = new SubagentRequest(
                "hook agent", "훅 테스트", null, null,
                SubagentRequest.IsolationMode.NONE, false, 30_000,
                Map.of(), "parent-sess-1");

        runner.run(request);

        // SUBAGENT_START + SUBAGENT_STOP 훅 2회 호출
        verify(hookDispatcher, times(2)).dispatch(any(), any());
    }
}
