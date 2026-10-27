package com.platform.workflow.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.config.PlatformSettingsService;
import com.platform.domain.ConnectionGroupEntity;
import com.platform.llm.ConnectionAdapterFactory;
import com.platform.llm.ConnectionGroupSelector;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.model.*;
import com.platform.llm.router.ModelRouter;
import com.platform.repository.ConnectionGroupRepository;
import com.platform.schema.SchemaValidator;
import com.platform.service.PromptTemplateService;
import com.platform.tenant.TenantContext;
import com.platform.workflow.StepContext;
import com.platform.workflow.event.WorkflowRunEventRecorder;
import com.platform.workflow.model.WorkflowStep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** CR-161 TC-CG-115: LLM_CALL 이 단계의 connection_group_id 로 run 단위 고정 멤버를 쓴다. */
class LlmCallStepExecutorGroupTest {

    private final UUID runId = UUID.randomUUID();
    private final StepContext context = new StepContext(runId.toString(), "wf-1", "sess-1", Map.of(), Map.of());

    private LLMAdapter w01;
    private LLMAdapter w02;
    private WorkflowRunEventRecorder recorder;
    private LlmCallStepExecutor executor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ConnectionAdapterFactory factory = mock(ConnectionAdapterFactory.class);
        w01 = mock(LLMAdapter.class);
        w02 = mock(LLMAdapter.class);
        when(factory.getAdapter("w01")).thenReturn(w01);
        when(factory.getAdapter("w02")).thenReturn(w02);
        when(factory.resolveModel(anyString(), anyString())).thenReturn("anthropic/claude-sonnet-4-5");
        when(factory.isCliConnection(anyString())).thenReturn(true);
        when(w01.chat(any(LLMRequest.class))).thenReturn(CompletableFuture.completedFuture(ok()));
        when(w02.chat(any(LLMRequest.class))).thenReturn(CompletableFuture.completedFuture(ok()));

        ConnectionGroupEntity group = new ConnectionGroupEntity();
        group.setId("grp");
        group.setName("grp");
        group.setAdapter("anthropic-cli");
        group.setStrategy("ROUND_ROBIN");
        group.setMembers(List.of(
                Map.of("connection_id", "w01", "priority", 1),
                Map.of("connection_id", "w02", "priority", 2)));
        ConnectionGroupRepository repo = mock(ConnectionGroupRepository.class);
        when(repo.findById("grp")).thenReturn(Optional.of(group));
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        when(settings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        ConnectionGroupSelector selector = new ConnectionGroupSelector(repo, factory, settings);

        recorder = mock(WorkflowRunEventRecorder.class);
        ObjectProvider<WorkflowRunEventRecorder> recorderProvider = mock(ObjectProvider.class);
        when(recorderProvider.getIfAvailable()).thenReturn(recorder);
        ObjectProvider<ConnectionGroupSelector> selectorProvider = mock(ObjectProvider.class);
        when(selectorProvider.getIfAvailable()).thenReturn(selector);

        executor = new LlmCallStepExecutor(mock(ModelRouter.class), factory, mock(PromptTemplateService.class),
                null, recorderProvider, new SchemaValidator(new ObjectMapper()), null, null, selectorProvider);
        TenantContext.setTenantId("bp_office");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void tc_cg_115_stepsInOneRunStayOnTheSameMember() {
        executor.execute(step("s1"), context);
        executor.execute(step("s2"), context);

        verify(w01, times(2)).chat(any(LLMRequest.class));
        verify(w02, never()).chat(any(LLMRequest.class));
        verify(recorder).llmRequest(eq(runId), eq("s1"), isNull(), anyString(), anyString(), eq("w01"));
        verify(recorder).llmRequest(eq(runId), eq("s2"), isNull(), anyString(), anyString(), eq("w01"));
    }

    @Test
    void tc_cg_115_accountLimitMovesTheRunToTheNextMember() {
        String limited = "cli_account_limited:resets_at=" + Instant.now().plusSeconds(3600).getEpochSecond() + ":type=seven_day:";
        when(w01.chat(any(LLMRequest.class))).thenReturn(CompletableFuture.failedFuture(new RuntimeException(limited)));

        executor.execute(step("s1"), context);
        executor.execute(step("s2"), context);

        verify(w01, times(1)).chat(any(LLMRequest.class));
        verify(w02, times(2)).chat(any(LLMRequest.class));
        verify(recorder).llmRequest(eq(runId), eq("s2"), isNull(), anyString(), anyString(), eq("w02"));
    }

    private static WorkflowStep step(String id) {
        Map<String, Object> config = new HashMap<>();
        config.put("connection_group_id", "grp");
        config.put("prompt", "hello");
        return new WorkflowStep(id, id, WorkflowStep.StepType.LLM_CALL, config, List.of(), null, null, null);
    }

    private static LLMResponse ok() {
        return new LLMResponse("m", "anthropic/claude-sonnet-4-5", List.of(new ContentBlock.Text("ok")),
                List.of(), new TokenUsage(10, 10), LLMResponse.FinishReason.END, 10L, 0.0);
    }
}
