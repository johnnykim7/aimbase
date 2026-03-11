package com.platform.llm;

import com.platform.llm.adapter.LLMAdapter;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class LLMAdapterRegistry {

    private static final Logger log = LoggerFactory.getLogger(LLMAdapterRegistry.class);

    private final Map<String, LLMAdapter> adapters;

    public LLMAdapterRegistry(List<LLMAdapter> adapterList) {
        this.adapters = adapterList.stream()
                .collect(Collectors.toMap(LLMAdapter::getProvider, Function.identity()));
    }

    @PostConstruct
    public void init() {
        log.info("Registered LLM adapters: {}", adapters.keySet());
    }

    /**
     * 모델 ID로 어댑터 조회
     * @param modelId "anthropic/claude-sonnet-4-5", "openai/gpt-4o", "ollama/llama3.2" 형식
     */
    public LLMAdapter getAdapter(String modelId) {
        String provider = extractProvider(modelId);
        LLMAdapter adapter = adapters.get(provider);
        if (adapter == null) {
            throw new IllegalArgumentException("No LLM adapter found for provider: " + provider + " (model: " + modelId + ")");
        }
        return adapter;
    }

    public boolean hasAdapter(String modelId) {
        return adapters.containsKey(extractProvider(modelId));
    }

    public List<String> availableProviders() {
        return List.copyOf(adapters.keySet());
    }

    public java.util.Collection<LLMAdapter> getAllAdapters() {
        return adapters.values();
    }

    private String extractProvider(String modelId) {
        if (modelId.contains("/")) {
            return modelId.split("/")[0];
        }
        // provider prefix 없으면 이름으로 추론
        if (modelId.startsWith("claude")) return "anthropic";
        if (modelId.startsWith("gpt")) return "openai";
        return "ollama";
    }
}
