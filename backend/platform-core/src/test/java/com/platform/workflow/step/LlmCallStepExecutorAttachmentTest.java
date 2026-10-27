package com.platform.workflow.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.llm.ConnectionAdapterFactory;
import com.platform.llm.adapter.AdapterCapability;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.model.*;
import com.platform.llm.router.ModelRouter;
import com.platform.schema.SchemaValidator;
import com.platform.service.PromptTemplateService;
import com.platform.workflow.StepContext;
import com.platform.workflow.model.WorkflowStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** CR-159: LLM 단계가 attachments 블록을 사용자 메시지에 싣는다. */
class LlmCallStepExecutorAttachmentTest {

    private static final String CONNECTION_ID = "cli-runner-bp-office-001";
    private static final String MODEL_ID = "anthropic-cli/claude-sonnet-4-5";

    private LLMAdapter adapter;
    private StepAttachmentResolver attachmentResolver;
    private LlmCallStepExecutor executor;

    private final List<ContentBlock> photo = List.of(
            new ContentBlock.Text("[첨부: 현장.jpg]"),
            ContentBlock.Image.ofBase64("image/jpeg", "QUJD"));

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        adapter = mock(LLMAdapter.class);
        when(adapter.capabilities()).thenReturn(AdapterCapability.IMAGE_AND_PDF);
        ConnectionAdapterFactory factory = mock(ConnectionAdapterFactory.class);
        when(factory.getAdapter(CONNECTION_ID)).thenReturn(adapter);
        when(factory.resolveModel(CONNECTION_ID, "auto")).thenReturn(MODEL_ID);
        attachmentResolver = mock(StepAttachmentResolver.class);
        ObjectProvider<StepAttachmentResolver> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(attachmentResolver);
        executor = new LlmCallStepExecutor(mock(ModelRouter.class), factory, mock(PromptTemplateService.class),
                null, null, new SchemaValidator(new ObjectMapper()), null, provider, null);
    }

    private static StepContext context() {
        return new StepContext("run-1", "bp-office-extraction", "bp-office-work-7",
                Map.of("text", "", "files", List.of("8a6e0f6e-4a39-4e43-9a3b-1f0c2d9c1b11")), Map.of());
    }

    private static WorkflowStep step(Map<String, Object> extra) {
        Map<String, Object> config = new HashMap<>();
        config.put("connection_id", CONNECTION_ID);
        config.put("system", "접수 담당");
        config.put("prompt", "원문: {{input.text}}");
        config.putAll(extra);
        return new WorkflowStep("extract", "뽑기", WorkflowStep.StepType.LLM_CALL, config, List.of(), null, null, 30000L);
    }

    private static LLMResponse text(String body) {
        return new LLMResponse("m", MODEL_ID, List.of(new ContentBlock.Text(body)), List.of(),
                new TokenUsage(10, 10), LLMResponse.FinishReason.END, 1L, 0.0);
    }

    private static UnifiedMessage lastUser(LLMRequest request) {
        List<UnifiedMessage> msgs = request.messages();
        return msgs.get(msgs.size() - 1);
    }

    @Test
    @DisplayName("TC-CR159-020: attachments 블록을 프롬프트 글 앞에 같은 사용자 메시지로 보낸다")
    void attachmentsGoBeforePromptInUserMessage() {
        when(attachmentResolver.resolve(eq("extract"), eq("{{input.files}}"), any(), eq(AdapterCapability.IMAGE_AND_PDF)))
                .thenReturn(new StepAttachmentResolver.Resolved(photo, "[첨부] 현장.jpg image/jpeg 3B, 그림"));
        when(adapter.chat(any())).thenReturn(CompletableFuture.completedFuture(text("ok")));

        executor.execute(step(Map.of("attachments", "{{input.files}}")), context());

        ArgumentCaptor<LLMRequest> captor = ArgumentCaptor.forClass(LLMRequest.class);
        verify(adapter).chat(captor.capture());
        UnifiedMessage user = lastUser(captor.getValue());
        assertThat(user.role()).isEqualTo(UnifiedMessage.Role.USER);
        assertThat(user.content()).containsExactly(photo.get(0), photo.get(1), new ContentBlock.Text("원문: "));
        assertThat(captor.getValue().messages().get(0).role()).isEqualTo(UnifiedMessage.Role.SYSTEM);
    }

    @Test
    @DisplayName("TC-CR159-021: attachments 칸이 없으면 지금처럼 글 한 덩이이고 첨부를 해석하지 않는다")
    void noAttachmentsKeepsTextOnly() {
        when(adapter.chat(any())).thenReturn(CompletableFuture.completedFuture(text("ok")));

        executor.execute(step(Map.of()), context());

        ArgumentCaptor<LLMRequest> captor = ArgumentCaptor.forClass(LLMRequest.class);
        verify(adapter).chat(captor.capture());
        assertThat(lastUser(captor.getValue()).content()).containsExactly(new ContentBlock.Text("원문: "));
        verifyNoInteractions(attachmentResolver);
    }

    @Test
    @DisplayName("TC-CR159-022: 구조화 결과를 다시 요청할 때도 첫 사용자 메시지에 첨부가 남아 있다")
    void structuredRetryKeepsAttachments() {
        when(attachmentResolver.resolve(any(), any(), any(), any()))
                .thenReturn(new StepAttachmentResolver.Resolved(photo, "[첨부] 현장.jpg"));
        when(adapter.chat(any()))
                .thenReturn(CompletableFuture.completedFuture(text("not json")))
                .thenReturn(CompletableFuture.completedFuture(new LLMResponse("m", MODEL_ID,
                        List.of(new ContentBlock.Structured("structured_output", Map.of("items", List.of()))),
                        List.of(), new TokenUsage(10, 10), LLMResponse.FinishReason.END, 1L, 0.0)));
        Map<String, Object> schema = Map.of("type", "object", "required", List.of("items"),
                "properties", Map.of("items", Map.of("type", "array")));

        Map<String, Object> result = executor.execute(
                step(Map.of("attachments", "{{input.files}}", "response_schema", schema)), context());

        ArgumentCaptor<LLMRequest> captor = ArgumentCaptor.forClass(LLMRequest.class);
        verify(adapter, times(2)).chat(captor.capture());
        LLMRequest retry = captor.getAllValues().get(1);
        assertThat(retry.messages().get(1).content()).startsWith(photo.get(0), photo.get(1));
        assertThat(result).containsKey("structured_data");
    }

    @Test
    @DisplayName("TC-CR159-023: 첨부 해석기가 없는 실행기에서 attachments 를 주면 조용히 빼지 않고 실패")
    void attachmentsWithoutResolverFails() {
        ConnectionAdapterFactory factory = mock(ConnectionAdapterFactory.class);
        when(factory.getAdapter(CONNECTION_ID)).thenReturn(adapter);
        when(factory.resolveModel(CONNECTION_ID, "auto")).thenReturn(MODEL_ID);
        LlmCallStepExecutor bare = new LlmCallStepExecutor(mock(ModelRouter.class), factory, mock(PromptTemplateService.class));

        assertThatThrownBy(() -> bare.execute(step(Map.of("attachments", "{{input.files}}")), context()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("attachments");
        verify(adapter, never()).chat(any());
    }
}
