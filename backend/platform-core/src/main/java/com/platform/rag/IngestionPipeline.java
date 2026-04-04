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

import org.springframework.lang.Nullable;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.*;
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
    @Nullable
    private final EmbeddingService embeddingService;
    private final DocumentParser documentParser;
    private final DocumentChunker documentChunker;
    private final IngestionLogRepository ingestionLogRepository;
    private final HttpClient httpClient;
    private final MCPRagClient mcpRagClient;
    private final com.platform.storage.StorageService storageService;

    @org.springframework.beans.factory.annotation.Value("${storage.local.base-path:/data/aimbase}")
    private String storageBasePath;

    public IngestionPipeline(KnowledgeSourceRepository knowledgeSourceRepository,
                              EmbeddingRepository embeddingRepository,
                              @Nullable EmbeddingService embeddingService,
                              DocumentParser documentParser,
                              DocumentChunker documentChunker,
                              IngestionLogRepository ingestionLogRepository,
                              MCPRagClient mcpRagClient,
                              com.platform.storage.StorageService storageService) {
        this.knowledgeSourceRepository = knowledgeSourceRepository;
        this.embeddingRepository = embeddingRepository;
        this.embeddingService = embeddingService;
        this.documentParser = documentParser;
        this.documentChunker = documentChunker;
        this.ingestionLogRepository = ingestionLogRepository;
        this.mcpRagClient = mcpRagClient;
        this.storageService = storageService;
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
        // 비동기 스레드에 테넌트 컨텍스트 전파
        String tenantId = com.platform.tenant.TenantContext.getTenantId();
        return CompletableFuture.supplyAsync(() -> {
            if (tenantId != null) {
                com.platform.tenant.TenantContext.setTenantId(tenantId);
            }
            try {
            return doIngestAsync(sourceId);
            } finally {
                com.platform.tenant.TenantContext.clear();
            }
        });
    }

    private IngestionResult doIngestAsync(String sourceId) {
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
    }

    // ─── 내부 동기 실행 ───────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private IngestionResult doIngest(KnowledgeSourceEntity source, IngestionLogEntity logEntity) {
        String type = source.getType();
        Map<String, Object> config = source.getConfig();
        Map<String, Object> chunkingConfig = source.getChunkingConfig();

        List<Map<String, Object>> errors = new ArrayList<>();
        int totalDocs = 0;
        int totalChunks = 0;

        // file 타입이고 MCP 사용 가능 시 사이드카가 파싱→청킹→임베딩→저장 전체 처리
        if ("file".equals(type) && mcpRagClient.isAvailable()) {
            String filePath = config != null ? (String) config.get("file_path") : null;
            if (filePath != null && !filePath.isBlank()) {
                String absBasePath = java.nio.file.Path.of(storageBasePath).toAbsolutePath().toString();
                String strategy = chunkingConfig != null
                        ? (String) chunkingConfig.getOrDefault("strategy", "fixed")
                        : "fixed";

                log.info("Delegating file ingestion to MCP sidecar: source='{}', file='{}', basePath='{}', model='{}'",
                        source.getId(), filePath, absBasePath, source.getEmbeddingModel());

                Map<String, Object> result = mcpRagClient.ingestFile(
                        source.getId(), filePath, absBasePath, filePath, strategy, chunkingConfig,
                        source.getEmbeddingModel());

                boolean success = Boolean.TRUE.equals(result.get("success"));
                int chunks = result.get("chunks_created") instanceof Number n ? n.intValue() : 0;

                if (!success) {
                    List<Map<String, Object>> mcpErrors = result.get("errors") instanceof List
                            ? (List<Map<String, Object>>) result.get("errors") : List.of();
                    errors.addAll(mcpErrors);
                }

                source.setDocumentCount(success ? 1 : 0);
                source.setChunkCount(chunks);

                return success && errors.isEmpty()
                        ? IngestionResult.success(source.getId(), 1, chunks)
                        : new IngestionResult(source.getId(), success, success ? 1 : 0, chunks, errors);
            }
        }

        // 소스 타입별 문서 수집
        List<RawDocument> documents = collectDocuments(type, config, source.getId(), errors);
        log.info("Collected {} documents for source '{}', type='{}', content lengths: {}",
                documents.size(), source.getId(), type,
                documents.stream().map(d -> d.documentId() + "=" + d.content().length()).toList());

        // MCP 사용 가능 시 Python 사이드카로 위임 (CR-002)
        if (mcpRagClient.isAvailable()) {
            return doIngestViaMCP(source, documents, chunkingConfig, errors, source.getEmbeddingModel());
        }

        // Fallback: 기존 Java 구현 (EmbeddingService 필수)
        if (embeddingService == null) {
            throw new IllegalStateException(
                    "EmbeddingService unavailable. Set spring.ai.openai.api-key or enable RAG MCP sidecar.");
        }
        boolean incremental = chunkingConfig != null
                && Boolean.TRUE.equals(chunkingConfig.get("incremental"));

        if (!incremental) {
            // 전체 재수집: 기존 임베딩 삭제
            int deleted = embeddingRepository.deleteBySourceId(source.getId());
            if (deleted > 0) {
                log.info("Deleted {} existing embeddings for source '{}'", deleted, source.getId());
            }
        }

        for (RawDocument doc : documents) {
            try {
                // 청킹
                List<Chunk> chunks = documentChunker.chunk(doc.documentId(), doc.content(), chunkingConfig);
                log.debug("Document '{}' → {} chunks", doc.documentId(), chunks.size());

                // PRD-126: incremental이면 해시 비교 후 변경분만 저장
                int savedChunks = incremental
                        ? embedAndSaveIncremental(source.getId(), chunks)
                        : embedAndSave(source.getId(), chunks);
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

    /**
     * Python MCP Server를 통한 인제스션 (CR-002).
     * 시맨틱 청킹(LlamaIndex) + 로컬 임베딩(KoSimCSE)을 Python 사이드카에서 실행.
     *
     * v2.0 (CR-011): contextual / parent_child 전략 지원.
     */
    @SuppressWarnings("unchecked")
    private IngestionResult doIngestViaMCP(KnowledgeSourceEntity source,
                                            List<RawDocument> documents,
                                            Map<String, Object> chunkingConfig,
                                            List<Map<String, Object>> errors,
                                            String embeddingModel) {
        int totalDocs = 0;
        int totalChunks = 0;

        String strategy = chunkingConfig != null
                ? (String) chunkingConfig.getOrDefault("strategy", "semantic")
                : "semantic";

        for (RawDocument doc : documents) {
            try {
                int chunks;
                if ("contextual".equals(strategy)) {
                    chunks = doContextualIngestViaMCP(source.getId(), doc, chunkingConfig, errors);
                } else if ("parent_child".equals(strategy)) {
                    chunks = doParentChildIngestViaMCP(source.getId(), doc, chunkingConfig, errors);
                } else {
                    // 기존 MCP ingest_document 호출
                    Map<String, Object> result = mcpRagClient.ingestDocument(
                            source.getId(), doc.content(), doc.documentId(),
                            strategy, chunkingConfig, embeddingModel);

                    boolean success = Boolean.TRUE.equals(result.get("success"));
                    chunks = result.get("chunks_created") instanceof Number n ? n.intValue() : 0;

                    if (!success) {
                        List<Map<String, Object>> mcpErrors = result.get("errors") instanceof List
                                ? (List<Map<String, Object>>) result.get("errors") : List.of();
                        errors.addAll(mcpErrors);
                    }
                }

                if (chunks > 0) {
                    totalChunks += chunks;
                    totalDocs++;
                }

                log.debug("MCP ingest '{}' strategy='{}' → {} chunks", doc.documentId(), strategy, chunks);
            } catch (Exception e) {
                log.warn("MCP ingestion failed for '{}': {}", doc.documentId(), e.getMessage());
                errors.add(Map.of("documentId", doc.documentId(), "error", e.getMessage()));
            }
        }

        source.setDocumentCount(totalDocs);
        log.info("MCP ingestion complete for '{}': {} docs, {} chunks, {} errors",
                source.getId(), totalDocs, totalChunks, errors.size());

        return errors.isEmpty()
                ? IngestionResult.success(source.getId(), totalDocs, totalChunks)
                : new IngestionResult(source.getId(), true, totalDocs, totalChunks, errors);
    }

    /**
     * PRD-116: Contextual Retrieval 인제스션 — MCP contextual_chunk → embed → store.
     * 각 청크에 LLM 생성 context_prefix를 부여하여 검색 정확도 향상.
     */
    @SuppressWarnings("unchecked")
    private int doContextualIngestViaMCP(String sourceId, RawDocument doc,
                                           Map<String, Object> chunkingConfig,
                                           List<Map<String, Object>> errors) {
        // 1. MCP contextual_chunk 호출
        Map<String, Object> chunkResult = mcpRagClient.contextualChunk(
                doc.content(), doc.content().substring(0, Math.min(2000, doc.content().length())), chunkingConfig);

        if (!Boolean.TRUE.equals(chunkResult.get("success"))) {
            errors.add(Map.of("documentId", doc.documentId(), "error", "contextual_chunk failed"));
            return 0;
        }

        List<Map<String, Object>> chunks = chunkResult.get("chunks") instanceof List
                ? (List<Map<String, Object>>) chunkResult.get("chunks") : List.of();

        // 2. 기존 임베딩 삭제 → 새 임베딩 저장
        embeddingRepository.deleteBySourceId(sourceId);

        int saved = 0;
        for (int i = 0; i < chunks.size(); i += BATCH_EMBED_SIZE) {
            List<Map<String, Object>> batch = chunks.subList(i, Math.min(i + BATCH_EMBED_SIZE, chunks.size()));

            // context_prefix + content를 결합하여 임베딩
            List<String> textsToEmbed = batch.stream()
                    .map(c -> {
                        String prefix = (String) c.getOrDefault("context_prefix", "");
                        String content = (String) c.get("content");
                        return prefix.isEmpty() ? content : prefix + "\n" + content;
                    })
                    .toList();

            List<float[]> vectors = embeddingService.embed(textsToEmbed);

            for (int j = 0; j < batch.size(); j++) {
                Map<String, Object> chunk = batch.get(j);
                EmbeddingEntity entity = new EmbeddingEntity();
                entity.setSourceId(sourceId);
                entity.setDocumentId(doc.documentId());
                entity.setChunkIndex(i + j);
                entity.setContent((String) chunk.get("content"));
                entity.setContextPrefix((String) chunk.getOrDefault("context_prefix", null));
                entity.setContentHash((String) chunk.getOrDefault("content_hash", null));
                entity.setEmbedding(embeddingService.vectorToString(vectors.get(j)));

                Map<String, Object> metadata = new HashMap<>();
                metadata.put("sourceId", sourceId);
                metadata.put("contextual", true);
                entity.setMetadata(metadata);

                embeddingRepository.save(entity);
                saved++;
            }
        }

        return saved;
    }

    /**
     * PRD-117: Parent-Child 계층적 인제스션.
     * 부모(큰 청크)와 자식(작은 청크)을 모두 저장, 자식에 parent_id 설정.
     */
    @SuppressWarnings("unchecked")
    private int doParentChildIngestViaMCP(String sourceId, RawDocument doc,
                                            Map<String, Object> chunkingConfig,
                                            List<Map<String, Object>> errors) {
        // MCP chunk_document로 큰/작은 청크 생성은 Python에서 처리
        // Java에서 parent-child 관계를 직접 구성

        // 1. 부모 청크 생성 (큰 윈도우)
        int parentSize = chunkingConfig != null
                ? ((Number) chunkingConfig.getOrDefault("parent_chunk_size", 1024)).intValue()
                : 1024;
        int childSize = chunkingConfig != null
                ? ((Number) chunkingConfig.getOrDefault("child_chunk_size", 256)).intValue()
                : 256;

        Map<String, Object> parentConfig = new HashMap<>(chunkingConfig != null ? chunkingConfig : Map.of());
        parentConfig.put("max_chunk_size", parentSize);

        Map<String, Object> parentResult = mcpRagClient.chunkDocument(doc.content(), "fixed", parentConfig);
        List<Map<String, Object>> parentChunks = parentResult.get("chunks") instanceof List
                ? (List<Map<String, Object>>) parentResult.get("chunks") : List.of();

        embeddingRepository.deleteBySourceId(sourceId);

        int saved = 0;
        for (int pi = 0; pi < parentChunks.size(); pi++) {
            String parentContent = (String) parentChunks.get(pi).get("content");
            String parentId = java.util.UUID.randomUUID().toString();
            String parentHash = hashSha256(parentContent);

            // 부모 청크 저장 (임베딩은 검색에 직접 사용하지 않지만 content 보관용)
            EmbeddingEntity parentEntity = new EmbeddingEntity();
            parentEntity.setSourceId(sourceId);
            parentEntity.setDocumentId(doc.documentId());
            parentEntity.setChunkIndex(pi);
            parentEntity.setContent(parentContent);
            parentEntity.setContentHash(parentHash);
            // 부모는 임베딩 없이 저장 (검색은 child가 담당)
            parentEntity.setEmbedding(embeddingService.vectorToString(embeddingService.embed(parentContent)));

            Map<String, Object> parentMeta = new HashMap<>();
            parentMeta.put("sourceId", sourceId);
            parentMeta.put("role", "parent");
            parentEntity.setMetadata(parentMeta);
            embeddingRepository.save(parentEntity);
            saved++;

            // 자식 청크 생성 (작은 윈도우)
            List<String> children = splitFixed(parentContent, childSize, 50);
            List<float[]> childVectors = embeddingService.embed(children);

            for (int ci = 0; ci < children.size(); ci++) {
                EmbeddingEntity childEntity = new EmbeddingEntity();
                childEntity.setSourceId(sourceId);
                childEntity.setDocumentId(doc.documentId());
                childEntity.setChunkIndex(pi * 100 + ci);
                childEntity.setContent(children.get(ci));
                childEntity.setParentId(parentId);
                childEntity.setContentHash(hashSha256(children.get(ci)));
                childEntity.setEmbedding(embeddingService.vectorToString(childVectors.get(ci)));

                Map<String, Object> childMeta = new HashMap<>();
                childMeta.put("sourceId", sourceId);
                childMeta.put("role", "child");
                childMeta.put("parent_index", pi);
                childEntity.setMetadata(childMeta);
                embeddingRepository.save(childEntity);
                saved++;
            }
        }

        return saved;
    }

    private static String hashSha256(String text) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static List<String> splitFixed(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            chunks.add(text.substring(start, end));
            start = end - overlap;
            if (start >= text.length()) break;
        }
        return chunks;
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
            case "file" -> {
                Object filePath = config.get("file_path");
                if (filePath instanceof String path && !path.isBlank()) {
                    try {
                        java.io.InputStream is = storageService.load(path);
                        String fileName = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
                        String mimeType = fileName.endsWith(".pdf") ? "application/pdf"
                                : fileName.endsWith(".docx") ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                                : fileName.endsWith(".pptx") ? "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                                : fileName.endsWith(".html") ? "text/html"
                                : "text/plain";
                        String parsed;
                        if ("text/plain".equals(mimeType) || fileName.endsWith(".md") || fileName.endsWith(".csv")) {
                            parsed = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
                        } else {
                            parsed = documentParser.parse(is, mimeType);
                        }
                        log.info("Parsed file '{}' ({}): {} chars", fileName, mimeType, parsed.length());
                        docs.add(new RawDocument(fileName, parsed));
                    } catch (Exception e) {
                        log.warn("Failed to read file '{}': {}", filePath, e.getMessage());
                        errors.add(Map.of("file_path", filePath.toString(), "error", e.getMessage()));
                    }
                } else {
                    log.warn("No file_path in config for source '{}'", sourceId);
                    errors.add(Map.of("sourceId", sourceId, "error", "No file_path configured. Upload a file first."));
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

    /**
     * PRD-126: Incremental Ingestion.
     * SHA-256 content hash로 변경된 청크만 재임베딩 (BIZ-029).
     * 기존과 동일한 content_hash를 가진 청크는 스킵.
     */
    private int embedAndSaveIncremental(String sourceId, List<Chunk> chunks) {
        // 기존 해시 목록 조회
        List<String> existingHashes = embeddingRepository.findContentHashesBySourceId(sourceId);
        Set<String> hashSet = new java.util.HashSet<>(existingHashes);

        List<Chunk> newChunks = new ArrayList<>();
        List<String> keepHashes = new ArrayList<>();

        for (Chunk chunk : chunks) {
            String hash = hashSha256(chunk.content());
            keepHashes.add(hash);
            if (!hashSet.contains(hash)) {
                newChunks.add(chunk);
            }
        }

        // 더 이상 존재하지 않는 청크 삭제
        int deleted = embeddingRepository.deleteBySourceIdAndHashNotIn(sourceId, keepHashes);
        if (deleted > 0) {
            log.info("Incremental: deleted {} stale embeddings for source '{}'", deleted, sourceId);
        }

        if (newChunks.isEmpty()) {
            log.info("Incremental: no new chunks for source '{}' (all {} unchanged)", sourceId, chunks.size());
            return 0;
        }

        log.info("Incremental: {} new/changed chunks out of {} total for source '{}'",
                newChunks.size(), chunks.size(), sourceId);

        // 새 청크만 임베딩 + 저장 (content_hash 포함)
        int saved = 0;
        for (int i = 0; i < newChunks.size(); i += BATCH_EMBED_SIZE) {
            List<Chunk> batch = newChunks.subList(i, Math.min(i + BATCH_EMBED_SIZE, newChunks.size()));
            List<String> texts = batch.stream().map(Chunk::content).toList();
            List<float[]> vectors = embeddingService.embed(texts);

            for (int j = 0; j < batch.size(); j++) {
                Chunk chunk = batch.get(j);
                float[] vector = vectors.get(j);

                EmbeddingEntity entity = new EmbeddingEntity();
                entity.setSourceId(sourceId);
                entity.setDocumentId(chunk.documentId());
                entity.setChunkIndex(chunk.chunkIndex());
                entity.setContent(chunk.content());
                entity.setContentHash(hashSha256(chunk.content()));
                entity.setEmbedding(embeddingService.vectorToString(vector));

                Map<String, Object> metadata = new HashMap<>(chunk.metadata() != null ? chunk.metadata() : Map.of());
                metadata.put("sourceId", sourceId);
                metadata.put("incremental", true);
                entity.setMetadata(metadata);

                embeddingRepository.save(entity);
                saved++;
            }
        }
        return saved;
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
