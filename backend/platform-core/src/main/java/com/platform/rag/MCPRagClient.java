package com.platform.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.mcp.MCPServerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;

/**
 * Python RAG Pipeline MCP Server 호출 클라이언트.
 *
 * Spring MCP Client → Python FastMCP Server (JSON-RPC over SSE) 방식으로
 * 기존 Java IngestionPipeline/VectorSearcher 기능을 Python 사이드카에 위임.
 *
 * v2.0 (CR-002): AI 특화 기능을 Python MCP Server로 전환.
 */
@Component
public class MCPRagClient {

    private static final Logger log = LoggerFactory.getLogger(MCPRagClient.class);

    private final ObjectMapper objectMapper;

    @Value("${rag.mcp.url:http://localhost:8000}")
    private String mcpServerUrl;

    @Value("${rag.mcp.enabled:false}")
    private boolean mcpEnabled;

    private MCPServerClient mcpClient;
    private boolean connected = false;

    public MCPRagClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        if (mcpEnabled) {
            try {
                mcpClient = new MCPServerClient("rag-pipeline", "sse", Map.of("url", mcpServerUrl));
                mcpClient.connect();
                connected = true;
                log.info("Connected to RAG Pipeline MCP Server at {}", mcpServerUrl);
            } catch (Exception e) {
                log.warn("Failed to connect to RAG Pipeline MCP Server at {}: {}. Falling back to Java implementation.",
                        mcpServerUrl, e.getMessage());
                connected = false;
            }
        }
    }

    @PreDestroy
    public void destroy() {
        if (mcpClient != null) {
            mcpClient.close();
        }
    }

    public boolean isAvailable() {
        return mcpEnabled && connected;
    }

    /**
     * Python MCP Server의 ingest_document 도구 호출.
     *
     * @return {source_id, document_id, chunks_created, success, errors}
     */
    public Map<String, Object> ingestDocument(String sourceId, String content,
                                                String documentId, String chunkingStrategy,
                                                Map<String, Object> chunkingConfig,
                                                String embeddingModel) {
        Map<String, Object> input = Map.of(
                "source_id", sourceId,
                "content", content,
                "document_id", documentId != null ? documentId : "",
                "chunking_strategy", chunkingStrategy != null ? chunkingStrategy : "semantic",
                "chunking_config", toJsonString(chunkingConfig != null ? chunkingConfig : Map.of()),
                "embedding_model", embeddingModel != null ? embeddingModel : ""
        );

        String result = mcpClient.callTool("ingest_document", input);
        return parseJson(result);
    }

    /**
     * Python MCP Server의 search_hybrid 도구 호출.
     *
     * @return {query, results: [{content, metadata, vector_score, keyword_score, combined_score}]}
     */
    public Map<String, Object> searchHybrid(String query, String sourceId, int topK,
                                              double vectorWeight, double keywordWeight) {
        Map<String, Object> input = Map.of(
                "query", query,
                "source_id", sourceId,
                "top_k", topK,
                "vector_weight", vectorWeight,
                "keyword_weight", keywordWeight
        );

        String result = mcpClient.callTool("search_hybrid", input);
        return parseJson(result);
    }

    /**
     * Python MCP Server의 rerank_results 도구 호출.
     *
     * @return {results: [{content, metadata, rerank_score}]}
     */
    public Map<String, Object> rerankResults(String query, List<Map<String, Object>> documents,
                                               int topK) {
        Map<String, Object> input = Map.of(
                "query", query,
                "documents", toJsonString(documents),
                "top_k", topK
        );

        String result = mcpClient.callTool("rerank_results", input);
        return parseJson(result);
    }

    /**
     * Python MCP Server의 chunk_document 도구 호출.
     */
    public Map<String, Object> chunkDocument(String content, String strategy,
                                               Map<String, Object> config) {
        Map<String, Object> input = Map.of(
                "content", content,
                "strategy", strategy != null ? strategy : "semantic",
                "config", toJsonString(config != null ? config : Map.of())
        );

        String result = mcpClient.callTool("chunk_document", input);
        return parseJson(result);
    }

    /**
     * Python MCP Server의 embed_texts 도구 호출.
     */
    public Map<String, Object> embedTexts(List<String> texts, String model) {
        Map<String, Object> input = Map.of(
                "texts", texts,
                "model", model != null ? model : ""
        );

        String result = mcpClient.callTool("embed_texts", input);
        return parseJson(result);
    }

    private String toJsonString(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize to JSON", e);
        }
    }

    private Map<String, Object> parseJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse MCP response: " + json, e);
        }
    }
}
