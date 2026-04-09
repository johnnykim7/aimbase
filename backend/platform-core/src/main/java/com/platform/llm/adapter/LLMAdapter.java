package com.platform.llm.adapter;

import com.platform.llm.model.LLMRequest;
import com.platform.llm.model.LLMResponse;
import com.platform.llm.model.LLMStreamChunk;
import com.platform.llm.model.ToolCall;
import com.platform.tool.model.UnifiedToolDef;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface LLMAdapter {

    String getProvider();

    List<String> getSupportedModels();

    CompletableFuture<LLMResponse> chat(LLMRequest request);

    void chatStream(LLMRequest request, Consumer<LLMStreamChunk> chunkConsumer);

    Object transformToolDefs(List<UnifiedToolDef> tools);

    List<ToolCall> parseToolCalls(Object nativeResponse);

    default boolean supports(String modelId) {
        String provider = modelId.split("/")[0];
        return getProvider().equals(provider);
    }
}
