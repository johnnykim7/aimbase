package com.platform.workflow.step;

import com.platform.attachment.AttachmentService;
import com.platform.config.PlatformSettingsService;
import com.platform.config.WorkspaceProperties;
import com.platform.domain.ConnectionGroupEntity;
import com.platform.largeinput.*;
import com.platform.llm.ConnectionAdapterFactory;
import com.platform.llm.ConnectionGroupSelector;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.model.*;
import com.platform.llm.router.ModelRouter;
import com.platform.rag.MCPRagClient;
import com.platform.repository.ConnectionGroupRepository;
import com.platform.repository.LargeInputJobRepository;
import com.platform.tenant.TenantContext;
import com.platform.workflow.analysis.AnalysisActionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CR-161 TC-CG-117: LARGE_INPUT 은 청크 호출마다 그룹을 거친다. 중간 청크에서 계정 한도가 나면 그 청크부터 다음 멤버로 간다.
 *
 * <p>execute 전체는 e2e 영역이라(LargeInputStepExecutorSourceTest 와 같은 이유) 청크 호출 단위를 리플렉션으로 부른다.
 */
class LargeInputStepExecutorGroupTest {

    private LLMAdapter w01;
    private LLMAdapter w02;
    private LargeInputStepExecutor executor;

    @BeforeEach
    void setUp() {
        ConnectionAdapterFactory factory = mock(ConnectionAdapterFactory.class);
        w01 = mock(LLMAdapter.class);
        w02 = mock(LLMAdapter.class);
        when(factory.getAdapter("w01")).thenReturn(w01);
        when(factory.getAdapter("w02")).thenReturn(w02);
        when(factory.resolveModel(anyString(), any())).thenReturn("anthropic/claude-sonnet-4-5");
        when(factory.isCliConnection(anyString())).thenReturn(true);

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

        executor = new LargeInputStepExecutor(
                mock(AttachmentService.class),
                List.of(),
                mock(LargeInputDecomposerService.class),
                mock(HierarchicalReduceService.class),
                mock(CoverageVerifier.class),
                mock(AnalysisActionRegistry.class),
                mock(LargeInputJobRepository.class),
                factory,
                mock(ModelRouter.class),
                mock(MCPRagClient.class),
                mock(PopplerPdfRenderer.class),
                new WorkspaceProperties(),
                mock(com.platform.workflow.event.WorkflowRunEventRecorder.class),
                new com.platform.agent.ActiveCliWorkerRegistry(),
                new com.platform.workflow.WorkflowCancellationRegistry(),
                new ConnectionGroupSelector(repo, factory, settings));
        TenantContext.setTenantId("bp_wes");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void tc_cg_117_limitInSecondChunkMovesRemainingChunksToNextMember() throws Exception {
        String limited = "cli_account_limited:resets_at=" + Instant.now().plusSeconds(3600).getEpochSecond() + ":type=seven_day:";
        when(w01.chat(any(LLMRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(ok()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(limited)));
        when(w02.chat(any(LLMRequest.class))).thenReturn(CompletableFuture.completedFuture(ok()));

        Object llm = stepLlm("grp", "run-1");
        List<String> requested = new ArrayList<>();
        BiConsumer<String, String> beforeCall = (conn, model) -> requested.add(conn);
        callLlm(llm, beforeCall);
        callLlm(llm, beforeCall);
        callLlm(llm, beforeCall);

        assertThat(requested).containsExactly("w01", "w01", "w02", "w02");
        verify(w01, times(2)).chat(any(LLMRequest.class));
        verify(w02, times(2)).chat(any(LLMRequest.class));
    }

    private Object stepLlm(String groupId, String runId) throws Exception {
        Method m = LargeInputStepExecutor.class.getDeclaredMethod(
                "stepLlm", String.class, String.class, String.class, String.class);
        m.setAccessible(true);
        return m.invoke(executor, null, groupId, "auto", runId);
    }

    private void callLlm(Object llm, BiConsumer<String, String> beforeCall) throws Exception {
        Method m = null;
        for (Method c : LargeInputStepExecutor.class.getDeclaredMethods()) {
            if (c.getName().equals("callLlm") && c.getParameterCount() == 8) m = c;
        }
        assertThat(m).isNotNull();
        m.setAccessible(true);
        try {
            m.invoke(executor, llm, "sys", List.of(new ContentBlock.Text("chunk")), null, 1024, "sess-1", null, beforeCall);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    private static LLMResponse ok() {
        return new LLMResponse("m", "anthropic/claude-sonnet-4-5", List.of(new ContentBlock.Text("ok")),
                List.of(), new TokenUsage(10, 10), LLMResponse.FinishReason.END, 10L, 0.0);
    }
}
