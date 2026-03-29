package com.platform.llm.router;

import com.platform.llm.LLMAdapterRegistry;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FallbackChainExecutorTest {

    private LLMAdapterRegistry registry;
    private FallbackChainExecutor executor;
    private LLMAdapter primaryAdapter;
    private LLMAdapter fallbackAdapter;

    @BeforeEach
    void setUp() {
        registry = mock(LLMAdapterRegistry.class);
        executor = new FallbackChainExecutor(registry);
        primaryAdapter = mock(LLMAdapter.class);
        fallbackAdapter = mock(LLMAdapter.class);
    }

    @Test
    void primarySuccess_returnsImmediately() throws Exception {
        LLMResponse expected = makeResponse("primary-ok");
        when(primaryAdapter.chat(any())).thenReturn(CompletableFuture.completedFuture(expected));

        LLMRequest request = new LLMRequest("model-a", List.of());
        LLMResponse result = executor.execute(request, primaryAdapter, "model-a", List.of("model-b"));

        assertThat(result.textContent()).isEqualTo("primary-ok");
        verify(primaryAdapter, times(1)).chat(any());
        verifyNoInteractions(registry);
    }

    @Test
    void primaryFails_fallbackSucceeds() throws Exception {
        when(primaryAdapter.chat(any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("timeout")));

        LLMResponse fallbackResponse = makeResponse("fallback-ok");
        when(fallbackAdapter.chat(any())).thenReturn(CompletableFuture.completedFuture(fallbackResponse));
        when(registry.getAdapter("model-b")).thenReturn(fallbackAdapter);

        LLMRequest request = new LLMRequest("model-a", List.of());
        LLMResponse result = executor.execute(request, primaryAdapter, "model-a", List.of("model-b"));

        assertThat(result.textContent()).isEqualTo("fallback-ok");
        verify(primaryAdapter, times(1)).chat(any());
        verify(fallbackAdapter, times(1)).chat(any());
    }

    @Test
    void allFail_throwsException() {
        when(primaryAdapter.chat(any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("fail-1")));

        LLMAdapter fallback1 = mock(LLMAdapter.class);
        when(fallback1.chat(any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("fail-2")));
        when(registry.getAdapter("model-b")).thenReturn(fallback1);

        LLMRequest request = new LLMRequest("model-a", List.of());
        assertThatThrownBy(() -> executor.execute(request, primaryAdapter, "model-a", List.of("model-b")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("All models in fallback chain failed");
    }

    @Test
    void noFallbackModels_throwsOnPrimaryFailure() {
        when(primaryAdapter.chat(any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("fail")));

        LLMRequest request = new LLMRequest("model-a", List.of());
        assertThatThrownBy(() -> executor.execute(request, primaryAdapter, "model-a", List.of()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("no fallback models configured");
    }

    private LLMResponse makeResponse(String text) {
        return new LLMResponse(
                "test-id", "test-model",
                List.of(new ContentBlock.Text(text)),
                List.of(), new TokenUsage(10, 20),
                LLMResponse.FinishReason.END, 100, 0.001);
    }
}
