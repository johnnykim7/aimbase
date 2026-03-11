package com.platform.rag;

import com.platform.domain.EmbeddingEntity;
import com.platform.domain.IngestionLogEntity;
import com.platform.domain.KnowledgeSourceEntity;
import com.platform.rag.model.Chunk;
import com.platform.rag.model.IngestionResult;
import com.platform.repository.EmbeddingRepository;
import com.platform.repository.IngestionLogRepository;
import com.platform.repository.KnowledgeSourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 지식 소스 수집 파이프라인.
 * parse → chunk → embed → store 흐름을 비동기(Virtual Thread)로 실행.
 *
 * 지원 소스 타입:
 * - "text": config.content 필드를 단일 문서로 처리
 * - "url": config.urls 목록에서 HTTP 페치 → Tika 파싱
 */
@Component
public class IngestionPipeline {

    private static final Logger log = LoggerFactory.getLogger(IngestionPipeline.class);
    private static final int BATCH_EMBED_SIZE = 20;

    private final KnowledgeSourceRepository knowledgeSourceRepository;
    private final EmbeddingRepository embeddingRepository;
    private final EmbeddingService embeddingService;
    private final DocumentParser documentParser;
    private final DocumentChunker documentChunker;
    private final IngestionLogRepository ingestionLogRepository;
    private final HttpClient httpClient;

    public IngestionPipeline(KnowledgeSourceRepository knowledgeSourceRepository,
                              EmbeddingRepository embeddingRepository,
                              EmbeddingService embeddingService,
                              DocumentParser documentParser,
                              DocumentChunker documentChunker,
                              IngestionLogRepository ingestionLogRepository) {
        this.knowledgeSourceRepository = knowledgeSourceRepository;
        this.embeddingRepository = embeddingRepository;
        this.embeddingService = embeddingService;
        this.documentParser = documentParser;
        this.documentChunker = documentChunker;
        this.ingestionLogRepository = ingestionLogRepository;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .build();
    }

    /**
     * 비동기 수집 시작. Virtual Thread에서 실행.
     *
     * @param sourceId 지식 소스 ID
     * @return IngestionResult를 담은 CompletableFuture
     */
    public CompletableFuture<IngestionResult> ingest(String sourceId) {
        return CompletableFuture.supplyAsync(() -> {
            KnowledgeSourceEntity source = knowledgeSourceRepository.findById(sourceId)
                    .orElseThrow(() -> new IllegalArgumentException("Knowledge source not found: " + sourceId));

            // 수집 로그 생성
            IngestionLogEntity logEntity = new IngestionLogEntity();
            logEntity.setSourceId(sourceId);
            logEntity.setMode("manual");
            logEntity.setStatus("running");
            ingestionLogRepository.save(logEntity);

            // 소스 상태 업데이트
            source.setStatus("syncing");
            knowledgeSourceRepository.save(source);

            try {
                IngestionResult result = doIngest(source, logEntity);

                // 성공 시 상태 업데이트
                logEntity.setStatus(result.success() ? "completed" : "failed");
                logEntity.setDocumentsProcessed(result.documentsProcessed());
                logEntity.setChunksCreated(result.chunksCreated());
                logEntity.setErrors(result.errors());
                logEntity.setCompletedAt(OffsetDateTime.now());
                ingestionLogRepository.save(logEntity);

                source.setStatus(result.success() ? "ready" : "error");
                source.setLastSyncedAt(OffsetDateTime.now());
                source.setChunkCount(embeddingRepository.countBySourceId(sourceId));
                knowledgeSourceRepository.save(source);

                return result;
            } catch (Exception e) {
                log.error("Ingestion failed for source '{}': {}", sourceId, e.getMessage(), e);

                logEntity.setStatus("failed");
                logEntity.setErrors(List.of(Map.of("error", e.getMessage())));
                logEntity.setCompletedAt(OffsetDateTime.now());
                ingestionLogRepository.save(logEntity);

                source.setStatus("error");
                knowledgeSourceRepository.save(source);

                return IngestionResult.failure(sourceId, List.of(Map.of("error", e.getMessage())));
            }
        });
    }

    // ─── 내부 동기 실행 ───────────────────────────────────────────────────

    private IngestionResult doIngest(KnowledgeSourceEntity source, IngestionLogEntity logEntity) {
        String type = source.getType();
        Map<String, Object> config = source.getConfig();
        Map<String, Object> chunkingConfig = source.getChunkingConfig();

        List<Map<String, Object>> errors = new ArrayList<>();
        int totalDocs = 0;
        int totalChunks = 0;

        // 기존 임베딩 삭제 (re-sync)
        int deleted = embeddingRepository.deleteBySourceId(source.getId());
        if (deleted > 0) {
            log.info("Deleted {} existing embeddings for source '{}'", deleted, source.getId());
        }

        // 소스 타입별 문서 수집
        List<RawDocument> documents = collectDocuments(type, config, source.getId(), errors);

        for (RawDocument doc : documents) {
            try {
                // 청킹
                List<Chunk> chunks = documentChunker.chunk(doc.documentId(), doc.content(), chunkingConfig);
                log.debug("Document '{}' → {} chunks", doc.documentId(), chunks.size());

                // 배치 임베딩 + 저장
                int savedChunks = embedAndSave(source.getId(), chunks);
                totalChunks += savedChunks;
                totalDocs++;
            } catch (Exception e) {
                log.warn("Failed to process document '{}': {}", doc.documentId(), e.getMessage());
                errors.add(Map.of("documentId", doc.documentId(), "error", e.getMessage()));
            }
        }

        source.setDocumentCount(totalDocs);
        log.info("Ingestion complete for '{}': {} docs, {} chunks, {} errors",
                source.getId(), totalDocs, totalChunks, errors.size());

        return errors.isEmpty()
                ? IngestionResult.success(source.getId(), totalDocs, totalChunks)
                : new IngestionResult(source.getId(), true, totalDocs, totalChunks, errors);
    }

    @SuppressWarnings("unchecked")
    private List<RawDocument> collectDocuments(String type, Map<String, Object> config,
                                                String sourceId, List<Map<String, Object>> errors) {
        List<RawDocument> docs = new ArrayList<>();

        switch (type) {
            case "text" -> {
                Object content = config.get("content");
                if (content instanceof String text && !text.isBlank()) {
                    docs.add(new RawDocument(sourceId + "-text", documentParser.parse(text)));
                }
            }
            case "url" -> {
                Object urlObj = config.get("urls");
                List<String> urls = urlObj instanceof List ? (List<String>) urlObj : List.of();
                if (urls.isEmpty() && config.get("url") instanceof String singleUrl) {
                    urls = List.of(singleUrl);
                }
                for (String url : urls) {
                    try {
                        String content = fetchUrl(url);
                        String parsed = documentParser.parse(
                                new ByteArrayInputStream(content.getBytes()), "text/html");
                        docs.add(new RawDocument(url, parsed));
                    } catch (Exception e) {
                        log.warn("Failed to fetch URL '{}': {}", url, e.getMessage());
                        errors.add(Map.of("url", url, "error", e.getMessage()));
                    }
                }
            }
            default -> log.warn("Unsupported source type '{}' for sourceId '{}'", type, sourceId);
        }

        return docs;
    }

    private int embedAndSave(String sourceId, List<Chunk> chunks) {
        int saved = 0;
        // 배치 처리
        for (int i = 0; i < chunks.size(); i += BATCH_EMBED_SIZE) {
            List<Chunk> batch = chunks.subList(i, Math.min(i + BATCH_EMBED_SIZE, chunks.size()));
            List<String> texts = batch.stream().map(Chunk::content).toList();
            List<float[]> vectors = embeddingService.embed(texts);

            List<EmbeddingEntity> entities = new ArrayList<>();
            for (int j = 0; j < batch.size(); j++) {
                Chunk chunk = batch.get(j);
                float[] vector = vectors.get(j);

                EmbeddingEntity entity = new EmbeddingEntity();
                entity.setSourceId(sourceId);
                entity.setDocumentId(chunk.documentId());
                entity.setChunkIndex(chunk.chunkIndex());
                entity.setContent(chunk.content());
                entity.setEmbedding(embeddingService.vectorToString(vector));

                Map<String, Object> metadata = new HashMap<>(chunk.metadata() != null ? chunk.metadata() : Map.of());
                metadata.put("sourceId", sourceId);
                entity.setMetadata(metadata);

                entities.add(entity);
            }

            embeddingRepository.saveAll(entities);
            saved += entities.size();
        }
        return saved;
    }

    private String fetchUrl(String urlStr) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(urlStr))
                .timeout(java.time.Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("HTTP " + response.statusCode() + " for URL: " + urlStr);
        }
        return response.body();
    }

    /** 내부 문서 전송 타입 */
    private record RawDocument(String documentId, String content) {}
}
