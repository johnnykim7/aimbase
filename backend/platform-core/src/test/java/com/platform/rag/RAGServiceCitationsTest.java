package com.platform.rag;

import com.platform.domain.KnowledgeSourceEntity;
import com.platform.rag.model.RetrievedChunk;
import com.platform.repository.KnowledgeSourceRepository;
import com.platform.repository.RetrievalConfigRepository;
import com.platform.service.PromptTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * CR-058: buildContextWithCitations 포맷 검증.
 */
@ExtendWith(MockitoExtension.class)
class RAGServiceCitationsTest {

    @Mock private VectorSearcher vectorSearcher;
    @Mock private RetrievalConfigRepository retrievalConfigRepository;
    @Mock private MCPRagClient mcpRagClient;
    @Mock private PromptTemplateService promptTemplateService;
    @Mock private KnowledgeSourceRepository knowledgeSourceRepository;

    private RAGService ragService;

    @BeforeEach
    void setUp() {
        ragService = new RAGService(vectorSearcher, retrievalConfigRepository, mcpRagClient,
                promptTemplateService, knowledgeSourceRepository);
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildContextWithCitations_returnsWidgetFormat() {
        when(retrievalConfigRepository.findById("src-1")).thenReturn(Optional.empty());
        when(retrievalConfigRepository.findByIsActiveTrue()).thenReturn(List.of());
        when(vectorSearcher.search(eq("질문"), eq("src-1"), eq(5)))
                .thenReturn(List.of(
                        new RetrievedChunk("chunk-abc", "반품 접수 후 7일 이내 처리합니다.",
                                0.87, Map.of("page_number", 4), "src-1"),
                        new RetrievedChunk("chunk-def", "환불은 원 결제 수단으로 환급됩니다.",
                                0.73, Map.of(), "src-1")));

        KnowledgeSourceEntity src = new KnowledgeSourceEntity();
        src.setId("src-1");
        src.setName("반품정책.pdf");
        when(knowledgeSourceRepository.findById("src-1")).thenReturn(Optional.of(src));

        Map<String, Object> result = ragService.buildContextWithCitations("질문", "src-1", 5);

        assertThat(result).containsKeys("context", "citations");
        String context = (String) result.get("context");
        assertThat(context).contains("[1]").contains("[2]").contains("반품 접수");

        List<Map<String, Object>> citations = (List<Map<String, Object>>) result.get("citations");
        assertThat(citations).hasSize(2);

        Map<String, Object> first = citations.get(0);
        assertThat(first.get("index")).isEqualTo(1);
        assertThat(first.get("chunk_id")).isEqualTo("chunk-abc");
        assertThat(first.get("source_id")).isEqualTo("src-1");
        assertThat(first.get("document_name")).isEqualTo("반품정책.pdf");
        assertThat(first.get("score")).isEqualTo(0.87);
        assertThat(first.get("content_preview")).asString().contains("반품 접수");
        assertThat(first.get("page_number")).isEqualTo(4);

        Map<String, Object> second = citations.get(1);
        assertThat(second.get("chunk_id")).isEqualTo("chunk-def");
        assertThat(second).doesNotContainKey("page_number"); // 메타 없으면 생략
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildContextWithCitations_emptyWhenNoChunks() {
        when(retrievalConfigRepository.findById("src-empty")).thenReturn(Optional.empty());
        when(retrievalConfigRepository.findByIsActiveTrue()).thenReturn(List.of());
        when(vectorSearcher.search(eq("질문"), eq("src-empty"), eq(5)))
                .thenReturn(List.of());

        Map<String, Object> result = ragService.buildContextWithCitations("질문", "src-empty", 5);

        assertThat((String) result.get("context")).isEmpty();
        assertThat((List<Map<String, Object>>) result.get("citations")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildContextWithCitations_fallsBackToSourceIdWhenNameMissing() {
        when(retrievalConfigRepository.findById("src-x")).thenReturn(Optional.empty());
        when(retrievalConfigRepository.findByIsActiveTrue()).thenReturn(List.of());
        when(vectorSearcher.search(eq("q"), eq("src-x"), eq(5)))
                .thenReturn(List.of(new RetrievedChunk("cx", "c", 0.5, Map.of(), "src-x")));
        when(knowledgeSourceRepository.findById("src-x")).thenReturn(Optional.empty());

        Map<String, Object> result = ragService.buildContextWithCitations("q", "src-x", 5);

        List<Map<String, Object>> citations = (List<Map<String, Object>>) result.get("citations");
        assertThat(citations.get(0).get("document_name")).isEqualTo("src-x");
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildContextWithCitations_previewTruncatesLongContent() {
        String longContent = "가".repeat(500);
        when(retrievalConfigRepository.findById("src-long")).thenReturn(Optional.empty());
        when(retrievalConfigRepository.findByIsActiveTrue()).thenReturn(List.of());
        when(vectorSearcher.search(eq("q"), eq("src-long"), eq(5)))
                .thenReturn(List.of(new RetrievedChunk("c-long", longContent, 0.9, Map.of(), "src-long")));

        Map<String, Object> result = ragService.buildContextWithCitations("q", "src-long", 5);

        List<Map<String, Object>> citations = (List<Map<String, Object>>) result.get("citations");
        String preview = (String) citations.get(0).get("content_preview");
        assertThat(preview).endsWith("…");
        assertThat(preview.length()).isLessThanOrEqualTo(201); // 200 + '…'
    }
}
