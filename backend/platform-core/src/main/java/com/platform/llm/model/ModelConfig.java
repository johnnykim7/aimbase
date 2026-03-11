package com.platform.llm.model;

import java.util.List;

public record ModelConfig(
        Double temperature,
        Integer maxTokens,
        Double topP,
        List<String> stopSequences
) {
    public static ModelConfig defaults() {
        return new ModelConfig(null, null, null, null);
    }
}
