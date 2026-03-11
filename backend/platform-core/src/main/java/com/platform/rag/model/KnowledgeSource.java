package com.platform.rag.model;

import java.util.Map;

public record KnowledgeSource(
        String id,
        String name,
        String type,
        Map<String, Object> config,
        Map<String, Object> chunkingConfig,
        Map<String, Object> embeddingConfig,
        Map<String, Object> syncConfig
) {}
