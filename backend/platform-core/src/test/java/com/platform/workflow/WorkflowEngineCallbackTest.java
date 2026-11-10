package com.platform.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.domain.WorkflowEntity;
import com.platform.domain.WorkflowRunEntity;
import com.platform.monitoring.PlatformMetrics;
import com.platform.repository.PendingApprovalRepository;
import com.platform.repository.WorkflowRepository;
import com.platform.repository.WorkflowRunRepository;
import com.platform.workflow.callback.RunCompletionCallback;
import com.platform.workflow.event.WorkflowEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("WorkflowEngine 실행 입력 _callback 칸 검사 (CR-174, BIZ-143)")
class WorkflowEngineCallbackTest {

    private WorkflowRepository workflowRepository;
    private WorkflowRunRepository runRepository;
    private RunCompletionCallback callback;
    private WorkflowEngine engine;

    @SuppressWarnings("unchecked")
    private static <T> org.springframework.beans.factory.ObjectProvider<T> provider(T value) {
        org.springframework.beans.factory.ObjectProvider<T> p =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    @BeforeEach
    void setUp() {
        workflowRepository = mock(WorkflowRepository.class);
        runRepository = mock(WorkflowRunRepository.class);
        callback = mock(RunCompletionCallback.class);
        when(runRepository.save(any())).thenAnswer(inv -> {
            WorkflowRunEntity r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });
        WorkflowEntity w = new WorkflowEntity();
        w.setId("wf");
        w.setSteps(List.of());
        when(workflowRepository.findByIdAndIsActiveTrue("wf")).thenReturn(Optional.of(w));
        engine = new WorkflowEngine(
                workflowRepository,
                runRepository,
                mock(PendingApprovalRepository.class),
                new ObjectMapper(),
                List.of(),
                mock(PlatformMetrics.class),
                mock(WorkflowEventPublisher.class),
                null,
                null,
                null,
                provider(mock(com.platform.agent.ActiveCliWorkerRegistry.class)),
                provider(mock(com.platform.llm.ConnectionAdapterFactory.class)),
                provider(new WorkflowCancellationRegistry()),
                null,
                provider(callback));
    }

    @Test
    @DisplayName("TC-WF-034 _callback 칸이 틀리면 execute 가 400 사유로 막고 run 을 저장하지 않는다")
    void invalidCallbackCreatesNoRun() {
        Map<String, Object> input = Map.of(RunCompletionCallback.INPUT_KEY, Map.of("connection_id", "bp-3plgw"));
        doThrow(new IllegalArgumentException("callback_invalid:path")).when(callback).validate(input);

        assertThatThrownBy(() -> engine.execute("wf", input, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("callback_invalid:path");
        verify(runRepository, never()).save(any());
    }

    @Test
    @DisplayName("TC-WF-034 맞는 칸이면 검사 뒤 run 을 저장한다")
    void validCallbackCreatesRun() {
        Map<String, Object> input = Map.of(RunCompletionCallback.INPUT_KEY,
                Map.of("connection_id", "bp-3plgw", "path", "/result"));

        engine.execute("wf", input, null);

        verify(callback).validate(input);
        verify(runRepository).save(any());
    }
}
