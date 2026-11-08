package com.platform.rag;

import com.platform.domain.EmbeddingEntity;
import com.platform.domain.KnowledgeSourceEntity;
import com.platform.rag.model.IngestionResult;
import com.platform.repository.EmbeddingRepository;
import com.platform.repository.IngestionLogRepository;
import com.platform.repository.KnowledgeSourceRepository;
import com.platform.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CR-170: 사이드카를 쓰는 동기화가 지식소스를 한 번만 비우고 모은 문서를 모두 남기는지 본다.
 * 문서 두 건이 나오는 url 유형을 쓰고, 페이지는 사이드카 수집(firecrawl)으로 받아 네트워크를 타지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class IngestionPipelineSyncTest {

    private static final String URL_1 = "https://docs.example/1";
    private static final String URL_2 = "https://docs.example/2";

    @Mock private KnowledgeSourceRepository knowledgeSourceRepository;
    @Mock private EmbeddingRepository embeddingRepository;
    @Mock private EmbeddingService embeddingService;
    @Mock private DocumentParser documentParser;
    @Mock private DocumentChunker documentChunker;
    @Mock private IngestionLogRepository ingestionLogRepository;
    @Mock private MCPRagClient mcpRagClient;
    @Mock private StorageService storageService;

    private IngestionPipeline pipeline;
    private KnowledgeSourceEntity source;

    @BeforeEach
    void setUp() {
        pipeline = new IngestionPipeline(knowledgeSourceRepository, embeddingRepository, embeddingService,
                documentParser, documentChunker, ingestionLogRepository, mcpRagClient, storageService);
        source = new KnowledgeSourceEntity();
        source.setId("src-1");
        when(knowledgeSourceRepository.findById("src-1")).thenReturn(Optional.of(source));
        when(mcpRagClient.isAvailable()).thenReturn(true);
    }

    private void twoUrlSource(String strategy) throws Exception {
        source.setType("url");
        source.setConfig(Map.of("urls", List.of(URL_1, URL_2), "crawl_mode", "firecrawl"));
        source.setChunkingConfig(new HashMap<>(Map.of("strategy", strategy)));
        when(mcpRagClient.scrapeUrl(anyString(), eq("firecrawl"), eq(1), eq(30000)))
                .thenReturn(Map.of("pages", List.of(Map.of("content", "<p>page</p>"))));
        when(documentParser.parse(any(InputStream.class), eq("text/html"))).thenReturn("page text");
    }

    @Test
    void syncDeletesSourceOnceBeforeDocumentsAndKeepsEveryDocument() throws Exception {
        // TC-RAG-006
        twoUrlSource("fixed");
        when(mcpRagClient.ingestDocument(eq("src-1"), anyString(), anyString(), eq("fixed"), any(), any()))
                .thenReturn(Map.of("success", true, "chunks_created", 2));

        IngestionResult result = pipeline.ingest("src-1").join();

        InOrder order = inOrder(embeddingRepository, mcpRagClient);
        order.verify(embeddingRepository).deleteBySourceId("src-1");
        order.verify(mcpRagClient).ingestDocument(eq("src-1"), eq("page text"), eq(URL_1), eq("fixed"), any(), any());
        order.verify(mcpRagClient).ingestDocument(eq("src-1"), eq("page text"), eq(URL_2), eq("fixed"), any(), any());
        verify(embeddingRepository, times(1)).deleteBySourceId("src-1");
        assertThat(result.success()).isTrue();
        assertThat(result.documentsProcessed()).isEqualTo(2);
        assertThat(result.chunksCreated()).isEqualTo(4);
    }

    @Test
    void syncWithNoCollectedDocumentsKeepsExistingEmbeddings() {
        // TC-RAG-007: api 유형은 문서 넣기로만 채운다. 동기화가 모을 문서가 없으니 지우지 않는다
        source.setType("api");
        source.setConfig(Map.of());

        pipeline.ingest("src-1").join();

        verify(embeddingRepository, never()).deleteBySourceId(anyString());
        verify(mcpRagClient, never()).ingestDocument(any(), any(), any(), any(), any(), any());
    }

    @Test
    void contextualSyncDeletesSourceOnceAndKeepsEveryDocument() throws Exception {
        // TC-RAG-008: 맥락 전략도 문서마다 지식소스 전체를 지우던 경로다
        twoUrlSource("contextual");
        when(mcpRagClient.contextualChunk(anyString(), anyString(), any()))
                .thenReturn(Map.of("success", true,
                        "chunks", List.of(Map.of("content", "chunk", "context_prefix", "prefix"))));
        when(embeddingService.embed(anyList())).thenReturn(List.of(new float[]{0.1f}));
        when(embeddingService.vectorToString(any())).thenReturn("[0.1]");

        pipeline.ingest("src-1").join();

        verify(embeddingRepository, times(1)).deleteBySourceId("src-1");
        ArgumentCaptor<EmbeddingEntity> saved = ArgumentCaptor.forClass(EmbeddingEntity.class);
        verify(embeddingRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(EmbeddingEntity::getDocumentId)
                .containsExactly(URL_1, URL_2);
    }
}
