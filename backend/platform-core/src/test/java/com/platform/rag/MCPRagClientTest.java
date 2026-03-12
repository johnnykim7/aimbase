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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * MCPRagClient 단위 테스트.
 *
 * Sprint 18: MCP 클라이언트 통합 — RAG Pipeline MCP 호출 검증.
 */
@ExtendWith(MockitoExtension.class)
class MCPRagClientTest {

    @Mock private MCPServerClient mcpServerClient;

    private MCPRagClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        client = new MCPRagClient(objectMapper);

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

    @Test
    void isAvailable_whenEnabledAndConnected_returnsTrue() {
        assertThat(client.isAvailable()).isTrue();
    }

    @Test
    void isAvailable_whenDisabled_returnsFalse() throws Exception {
        Field enabledField = MCPRagClient.class.getDeclaredField("mcpEnabled");
        enabledField.setAccessible(true);
        enabledField.set(client, false);

        assertThat(client.isAvailable()).isFalse();
    }

    // ─── ingestDocument ──────────────────────────────────────────────

    @Test
    void ingestDocument_shouldCallToolAndParseResult() {
        // given
        String response = """
                {"source_id": "src-1", "document_id": "doc-1", "chunks_created": 5, "success": true, "errors": []}
                """;
        when(mcpServerClient.callTool(eq("ingest_document"), any())).thenReturn(response);

        // when
        Map<String, Object> result = client.ingestDocument(
                "src-1", "테스트 문서 내용입니다.", "doc-1", "semantic", null, null);

        // then
        assertThat(result).containsEntry("source_id", "src-1");
        assertThat(result).containsEntry("success", true);
        assertThat(result.get("chunks_created")).isEqualTo(5);
        verify(mcpServerClient).callTool(eq("ingest_document"), any());
    }

    @Test
    void ingestDocument_withNullDefaults_shouldUseDefaultValues() {
        // given
        String response = """
                {"source_id": "src-1", "document_id": "", "chunks_created": 3, "success": true, "errors": []}
                """;
        when(mcpServerClient.callTool(eq("ingest_document"), any())).thenReturn(response);

        // when — null documentId, null strategy, null config, null model
        Map<String, Object> result = client.ingestDocument(
                "src-1", "내용", null, null, null, null);

        // then
        assertThat(result).containsEntry("success", true);
        verify(mcpServerClient).callTool(eq("ingest_document"), any());
    }

    // ─── searchHybrid ────────────────────────────────────────────────

    @Test
    void searchHybrid_shouldCallToolAndParseResults() {
        // given
        String response = """
                {
                  "query": "검색어",
                  "results": [
                    {"content": "결과1", "metadata": {}, "vector_score": 0.9, "keyword_score": 0.8, "combined_score": 0.87}
                  ]
                }
                """;
        when(mcpServerClient.callTool(eq("search_hybrid"), any())).thenReturn(response);

        // when
        Map<String, Object> result = client.searchHybrid("검색어", "src-1", 5, 0.7, 0.3);

        // then
        assertThat(result).containsKey("results");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
        assertThat(results).hasSize(1);
        assertThat(results.get(0)).containsEntry("content", "결과1");
        verify(mcpServerClient).callTool(eq("search_hybrid"), any());
    }

    // ─── rerankResults ───────────────────────────────────────────────

    @Test
    void rerankResults_shouldCallToolAndParseResults() {
        // given
        String response = """
                {"results": [{"content": "리랭크 결과", "metadata": {}, "rerank_score": 0.95}]}
                """;
        when(mcpServerClient.callTool(eq("rerank_results"), any())).thenReturn(response);

        // when
        List<Map<String, Object>> documents = List.of(
                Map.of("content", "문서1"),
                Map.of("content", "문서2"));
        Map<String, Object> result = client.rerankResults("쿼리", documents, 3);

        // then
        assertThat(result).containsKey("results");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
        assertThat(results).hasSize(1);
        assertThat(((Number) results.get(0).get("rerank_score")).doubleValue()).isEqualTo(0.95);
        verify(mcpServerClient).callTool(eq("rerank_results"), any());
    }

    // ─── chunkDocument ───────────────────────────────────────────────

    @Test
    void chunkDocument_shouldCallToolAndParseResult() {
        // given
        String response = """
                {"chunks": [{"content": "청크1", "index": 0}, {"content": "청크2", "index": 1}]}
                """;
        when(mcpServerClient.callTool(eq("chunk_document"), any())).thenReturn(response);

        // when
        Map<String, Object> result = client.chunkDocument("긴 문서 내용", "recursive", null);

        // then
        assertThat(result).containsKey("chunks");
        verify(mcpServerClient).callTool(eq("chunk_document"), any());
    }

    // ─── embedTexts ──────────────────────────────────────────────────

    @Test
    void embedTexts_shouldCallToolAndParseResult() {
        // given
        String response = """
                {"embeddings": [[0.1, 0.2, 0.3], [0.4, 0.5, 0.6]]}
                """;
        when(mcpServerClient.callTool(eq("embed_texts"), any())).thenReturn(response);

        // when
        Map<String, Object> result = client.embedTexts(List.of("텍스트1", "텍스트2"), null);

        // then
        assertThat(result).containsKey("embeddings");
        verify(mcpServerClient).callTool(eq("embed_texts"), any());
    }

    // ─── 에러 핸들링 ──────────────────────────────────────────────────

    @Test
    void callTool_whenMcpFails_shouldThrowException() {
        // given
        when(mcpServerClient.callTool(eq("search_hybrid"), any()))
                .thenThrow(new RuntimeException("Connection refused"));

        // when & then
        assertThatThrownBy(() -> client.searchHybrid("q", "src", 5, 0.7, 0.3))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Connection refused");
    }

    @Test
    void callTool_whenInvalidJson_shouldThrowException() {
        // given
        when(mcpServerClient.callTool(eq("search_hybrid"), any()))
                .thenReturn("not valid json");

        // when & then
        assertThatThrownBy(() -> client.searchHybrid("q", "src", 5, 0.7, 0.3))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to parse MCP response");
    }
}
