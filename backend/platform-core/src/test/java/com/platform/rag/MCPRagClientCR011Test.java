package com.platform.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.mcp.MCPServerClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CR-011 신규 MCP 도구 단위 테스트.
 * TC-RAG-PP-001~009: contextual_chunk, parent_child_search, evaluate_rag, callToolRaw
 */
@ExtendWith(MockitoExtension.class)
class MCPRagClientCR011Test {

    @Mock private MCPServerClient mcpServerClient;
    @Mock private com.platform.tool.ToolRegistry toolRegistry;

    private MCPRagClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        client = new MCPRagClient(objectMapper, toolRegistry);

        Field mcpClientField = MCPRagClient.class.getDeclaredField("mcpClient");
        mcpClientField.setAccessible(true);
        mcpClientField.set(client, mcpServerClient);

        Field connectedField = MCPRagClient.class.getDeclaredField("connected");
        connectedField.setAccessible(true);
        connectedField.set(client, true);

        Field enabledField = MCPRagClient.class.getDeclaredField("mcpEnabled");
        enabledField.setAccessible(true);
        enabledField.set(client, true);
    }

    // ─── PY-023: contextualChunk ─────────────────────────────────────

    @Test
    void contextualChunk_shouldCallToolAndReturnChunksWithPrefix() {
        // given
        String response = """
                {"chunks": [
                    {"content": "청크1", "context_prefix": "문서 앞부분", "content_hash": "abc123", "chunk_index": 0}
                ], "chunk_count": 1, "success": true}
                """;
        when(mcpServerClient.callTool(eq("contextual_chunk"), any())).thenReturn(response);

        // when
        Map<String, Object> result = client.contextualChunk("문서 전체 내용", "문서 요약", null);

        // then
        assertThat(result).containsEntry("success", true);
        assertThat(result).containsEntry("chunk_count", 1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chunks = (List<Map<String, Object>>) result.get("chunks");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).containsEntry("context_prefix", "문서 앞부분");
        assertThat(chunks.get(0)).containsEntry("content_hash", "abc123");
        verify(mcpServerClient).callTool(eq("contextual_chunk"), any());
    }

    @Test
    void contextualChunk_withNullConfig_shouldUseDefaults() {
        String response = """
                {"chunks": [], "chunk_count": 0, "success": true}
                """;
        when(mcpServerClient.callTool(eq("contextual_chunk"), any())).thenReturn(response);

        Map<String, Object> result = client.contextualChunk("내용", null, null);
        assertThat(result).containsEntry("success", true);
    }

    // ─── PY-024: parentChildSearch ──────────────────────────────────

    @Test
    void parentChildSearch_shouldReturnParentChunks() {
        String response = """
                {"results": [
                    {"content": "부모 청크 내용", "parent_id": "p-1", "score": 0.92, "metadata": {}}
                ], "result_count": 1, "success": true}
                """;
        when(mcpServerClient.callTool(eq("parent_child_search"), any())).thenReturn(response);

        Map<String, Object> result = client.parentChildSearch("검색 쿼리", "src-1", 5);

        assertThat(result).containsEntry("success", true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
        assertThat(results).hasSize(1);
        assertThat(results.get(0)).containsEntry("parent_id", "p-1");
        assertThat(((Number) results.get(0).get("score")).doubleValue()).isEqualTo(0.92);
    }

    // ─── PY-026: evaluateRag ─────────────────────────────────────────

    @Test
    void evaluateRag_shouldReturnRagasMetrics() {
        String response = """
                {"metrics": {"faithfulness": 0.85, "context_relevancy": 0.72},
                 "sample_count": 3, "elapsed_seconds": 5.2, "success": true}
                """;
        when(mcpServerClient.callTool(eq("evaluate_rag"), any())).thenReturn(response);

        List<Map<String, Object>> testSet = List.of(
                Map.of("question", "Python이란?"),
                Map.of("question", "머신러닝 설명"));

        Map<String, Object> result = client.evaluateRag("src-1", testSet, null);

        assertThat(result).containsEntry("success", true);
        assertThat(result).containsEntry("sample_count", 3);
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) result.get("metrics");
        assertThat(((Number) metrics.get("faithfulness")).doubleValue()).isEqualTo(0.85);
    }

    @Test
    void evaluateRag_withEmptyTestSet_shouldReturnError() {
        String response = """
                {"metrics": {}, "samples": [], "sample_count": 0, "success": false, "error": "test_set is empty"}
                """;
        when(mcpServerClient.callTool(eq("evaluate_rag"), any())).thenReturn(response);

        Map<String, Object> result = client.evaluateRag("src-1", List.of(), null);
        assertThat(result).containsEntry("success", false);
    }

    // ─── callToolRaw ─────────────────────────────────────────────────

    @Test
    void callToolRaw_shouldCallAnyToolByName() {
        String response = """
                {"hit": true, "response_text": "캐시된 응답", "similarity": 0.97}
                """;
        when(mcpServerClient.callTool(eq("cache_lookup"), any())).thenReturn(response);

        Map<String, Object> result = client.callToolRaw("cache_lookup",
                Map.of("query", "테스트", "source_id", "src-1", "threshold", 0.95));

        assertThat(result).containsEntry("hit", true);
        assertThat(result).containsEntry("response_text", "캐시된 응답");
        verify(mcpServerClient).callTool(eq("cache_lookup"), any());
    }

    @Test
    void callToolRaw_cacheStore_shouldReturnCacheId() {
        String response = """
                {"cache_id": "cache-001", "success": true}
                """;
        when(mcpServerClient.callTool(eq("cache_store"), any())).thenReturn(response);

        Map<String, Object> result = client.callToolRaw("cache_store",
                Map.of("query", "q", "source_id", "s", "response_text", "r",
                        "metadata", "{}", "ttl_hours", 24));

        assertThat(result).containsEntry("success", true);
        assertThat(result).containsKey("cache_id");
    }

    // ─── PY-025: transformQuery (LLM 업그레이드) ────────────────────

    @Test
    void transformQuery_hyde_shouldReturnHypothesisAndQueries() {
        String response = """
                {"original_query": "질문", "transformed_queries": ["질문", "가상 문서"],
                 "strategy_used": "hyde", "metadata": {"llm_used": true}}
                """;
        when(mcpServerClient.callTool(eq("transform_query"), any())).thenReturn(response);

        Map<String, Object> result = client.transformQuery("질문", "hyde");

        assertThat(result).containsEntry("strategy_used", "hyde");
        @SuppressWarnings("unchecked")
        List<String> queries = (List<String>) result.get("transformed_queries");
        assertThat(queries).hasSize(2);
    }

    @Test
    void transformQuery_multiQuery_shouldReturnVariations() {
        String response = """
                {"original_query": "질문", "transformed_queries": ["질문", "변형1", "변형2", "변형3"],
                 "strategy_used": "multi_query", "metadata": {"variation_count": 3, "llm_used": true}}
                """;
        when(mcpServerClient.callTool(eq("transform_query"), any())).thenReturn(response);

        Map<String, Object> result = client.transformQuery("질문", "multi_query");

        @SuppressWarnings("unchecked")
        List<String> queries = (List<String>) result.get("transformed_queries");
        assertThat(queries).hasSizeGreaterThanOrEqualTo(3);
    }
}
