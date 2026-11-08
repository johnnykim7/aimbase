package com.platform.api;

import com.platform.domain.KnowledgeSourceEntity;
import com.platform.rag.IngestionPipeline;
import com.platform.rag.MCPRagClient;
import com.platform.rag.VectorSearcher;
import com.platform.rag.model.RetrievedChunk;
import com.platform.repository.EmbeddingRepository;
import com.platform.repository.IngestionLogRepository;
import com.platform.repository.KnowledgeSourceRepository;
import com.platform.repository.ProjectResourceRepository;
import com.platform.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * CR-170: POST /knowledge-sources/search 결과 항목의 documentId, vectorScore.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeControllerSearchTest {

    @Mock private KnowledgeSourceRepository knowledgeSourceRepository;
    @Mock private IngestionLogRepository ingestionLogRepository;
    @Mock private EmbeddingRepository embeddingRepository;
    @Mock private IngestionPipeline ingestionPipeline;
    @Mock private MCPRagClient mcpRagClient;
    @Mock private VectorSearcher vectorSearcher;
    @Mock private StorageService storageService;
    @Mock private ProjectResourceRepository projectResourceRepository;

    private KnowledgeController controller;

    @BeforeEach
    void setUp() {
        controller = new KnowledgeController(
                knowledgeSourceRepository, ingestionLogRepository, embeddingRepository,
                ingestionPipeline, mcpRagClient, vectorSearcher, storageService,
                projectResourceRepository);
        KnowledgeSourceEntity source = new KnowledgeSourceEntity();
        source.setId("src-1");
        source.setName("axopm-entities");
        source.setEmbeddingModel("BAAI/bge-m3");
        when(knowledgeSourceRepository.findAll()).thenReturn(List.of(source));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> search(List<RetrievedChunk> chunks) {
        when(vectorSearcher.search("반품 오류", "src-1", 5, "BAAI/bge-m3")).thenReturn(chunks);
        var res = controller.search(new KnowledgeController.SearchRequest("반품 오류", "src-1", 5));
        return (List<Map<String, Object>>) res.data().get("results");
    }

    @Test
    void resultsCarryDocumentIdAndVectorScore() {
        // TC-RAG-010
        List<Map<String, Object>> results = search(List.of(
                new RetrievedChunk("c-1", "이슈 본문", 4.2, Map.of(), "src-1", "Issue_1", 0.82),
                new RetrievedChunk("c-2", "키워드로만 걸린 본문", 1.1, Map.of(), "src-1", "Issue_2", 0.0)));

        assertThat(results).hasSize(2);
        assertThat(results.get(0)).containsEntry("documentId", "Issue_1")
                .containsEntry("vectorScore", 0.82)
                .containsEntry("score", 4.2)
                .containsEntry("sourceId", "src-1")
                .containsEntry("sourceName", "axopm-entities");
        assertThat(results.get(1)).containsEntry("documentId", "Issue_2")
                .containsEntry("vectorScore", 0.0);
    }

    @Test
    void negativeCosineIsReportedAsZero() {
        // TC-RAG-011
        List<Map<String, Object>> results = search(List.of(
                new RetrievedChunk("c-1", "반대 방향", 0.5, Map.of(), "src-1", "Issue_3", -0.2)));

        assertThat(results.get(0)).containsEntry("vectorScore", 0.0);
    }
}
