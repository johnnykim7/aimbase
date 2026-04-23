package com.platform.api;

import com.platform.domain.KnowledgeSourceEntity;
import com.platform.rag.IngestionPipeline;
import com.platform.rag.MCPRagClient;
import com.platform.rag.VectorSearcher;
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
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * CR-058: GET /knowledge-sources/{sid}/chunks/{cid} 동작 검증.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeControllerChunkTest {

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
    }

    @Test
    void getChunk_returnsChunkWithSourceName() {
        UUID chunkId = UUID.randomUUID();
        KnowledgeSourceEntity src = new KnowledgeSourceEntity();
        src.setId("src-1");
        src.setName("반품정책.pdf");
        when(knowledgeSourceRepository.findById("src-1")).thenReturn(Optional.of(src));

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("page_number", 4);
        var detail = new EmbeddingRepository.ChunkDetail(
                chunkId, "src-1", "doc-1", 2,
                "원문 내용입니다.", meta, null, null);
        when(embeddingRepository.findChunkDetail(eq("src-1"), any(UUID.class)))
                .thenReturn(Optional.of(detail));

        ApiResponse<Map<String, Object>> res = controller.getChunk("src-1", chunkId.toString());

        assertThat(res.data().get("chunk_id")).isEqualTo(chunkId.toString());
        assertThat(res.data().get("document_name")).isEqualTo("반품정책.pdf");
        assertThat(res.data().get("content")).isEqualTo("원문 내용입니다.");
        assertThat(res.data().get("page_number")).isEqualTo(4);
        assertThat(res.data()).doesNotContainKey("parent_id");
    }

    @Test
    void getChunk_includesParentContentWhenPresent() {
        UUID chunkId = UUID.randomUUID();
        KnowledgeSourceEntity src = new KnowledgeSourceEntity();
        src.setId("src-1");
        src.setName("doc");
        when(knowledgeSourceRepository.findById("src-1")).thenReturn(Optional.of(src));

        var detail = new EmbeddingRepository.ChunkDetail(
                chunkId, "src-1", "doc-1", 2,
                "child content", Map.of(), "parent-uuid", "parent content");
        when(embeddingRepository.findChunkDetail(eq("src-1"), any(UUID.class)))
                .thenReturn(Optional.of(detail));

        ApiResponse<Map<String, Object>> res = controller.getChunk("src-1", chunkId.toString());

        assertThat(res.data().get("parent_id")).isEqualTo("parent-uuid");
        assertThat(res.data().get("parent_content")).isEqualTo("parent content");
    }

    @Test
    void getChunk_returns400OnInvalidUuid() {
        assertThatThrownBy(() -> controller.getChunk("src-1", "not-a-uuid"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("UUID");
    }

    @Test
    void getChunk_returns404WhenSourceMissing() {
        when(knowledgeSourceRepository.findById("src-x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.getChunk("src-x", UUID.randomUUID().toString()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Source not found");
    }

    @Test
    void getChunk_returns404WhenChunkMissing() {
        KnowledgeSourceEntity src = new KnowledgeSourceEntity();
        src.setId("src-1");
        src.setName("doc");
        when(knowledgeSourceRepository.findById("src-1")).thenReturn(Optional.of(src));
        when(embeddingRepository.findChunkDetail(eq("src-1"), any(UUID.class)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.getChunk("src-1", UUID.randomUUID().toString()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Chunk not found");
    }
}
