package com.platform.api;

import com.platform.domain.KnowledgeSourceEntity;
import com.platform.rag.IngestionPipeline;
import com.platform.rag.MCPRagClient;
import com.platform.rag.VectorSearcher;
import com.platform.rag.model.RetrievedChunk;
import com.platform.repository.IngestionLogRepository;
import com.platform.repository.EmbeddingRepository;
import com.platform.repository.KnowledgeSourceRepository;
import com.platform.repository.ProjectResourceRepository;
import com.platform.storage.StorageService;
import com.platform.tenant.ProjectContext;
import com.platform.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.platform.auth.UserPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/knowledge-sources")
@Tag(name = "Knowledge Sources", description = "RAG 지식 소스 관리")
public class KnowledgeController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeController.class);
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "pdf", "docx", "xlsx", "pptx", "csv", "txt", "md", "html"
    );

    private final KnowledgeSourceRepository knowledgeSourceRepository;
    private final IngestionLogRepository ingestionLogRepository;
    private final EmbeddingRepository embeddingRepository;
    private final IngestionPipeline ingestionPipeline;
    private final MCPRagClient mcpRagClient;
    private final VectorSearcher vectorSearcher;
    private final StorageService storageService;
    private final ProjectResourceRepository projectResourceRepository;

    public KnowledgeController(KnowledgeSourceRepository knowledgeSourceRepository,
                                IngestionLogRepository ingestionLogRepository,
                                EmbeddingRepository embeddingRepository,
                                IngestionPipeline ingestionPipeline,
                                MCPRagClient mcpRagClient,
                                VectorSearcher vectorSearcher,
                                StorageService storageService,
                                ProjectResourceRepository projectResourceRepository) {
        this.knowledgeSourceRepository = knowledgeSourceRepository;
        this.ingestionLogRepository = ingestionLogRepository;
        this.embeddingRepository = embeddingRepository;
        this.ingestionPipeline = ingestionPipeline;
        this.mcpRagClient = mcpRagClient;
        this.vectorSearcher = vectorSearcher;
        this.storageService = storageService;
        this.projectResourceRepository = projectResourceRepository;
    }

    @GetMapping
    @Operation(summary = "지식 소스 목록 조회")
    public ApiResponse<?> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String type,
            @RequestParam(required = false, name = "my") Boolean my
    ) {
        // CR-022: 사용자별 리소스 필터링
        if (Boolean.TRUE.equals(my)) {
            String userId = currentUserId();
            if (userId != null) return ApiResponse.ok(knowledgeSourceRepository.findByCreatedBy(userId));
        }
        // CR-021: 프로젝트 스코핑 — 할당된 리소스만 반환
        String projectId = ProjectContext.getProjectId();
        if (projectId != null) {
            List<String> ids = projectResourceRepository.findResourceIdsByProjectIdAndResourceType(projectId, "knowledge_source");
            if (ids.isEmpty()) return ApiResponse.ok(List.of());
            return ApiResponse.ok(knowledgeSourceRepository.findAllById(ids));
        }
        var pageable = PageRequest.of(page, size);
        if (type != null) {
            return ApiResponse.ok(knowledgeSourceRepository.findByType(type));
        }
        return ApiResponse.page(knowledgeSourceRepository.findAll(pageable));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "지식 소스 생성")
    public ApiResponse<KnowledgeSourceEntity> create(@Valid @RequestBody KnowledgeSourceRequest request) {
        KnowledgeSourceEntity entity = new KnowledgeSourceEntity();
        entity.setId(request.id() != null && !request.id().isBlank() ? request.id() : java.util.UUID.randomUUID().toString());
        entity.setName(request.name());
        entity.setType(request.type());
        entity.setConfig(request.config() != null ? request.config() : Map.of());
        entity.setChunkingConfig(request.chunkingConfig() != null
                ? request.chunkingConfig()
                : Map.of("strategy", "fixed", "size", 512, "overlap", 50));
        entity.setEmbeddingConfig(request.embeddingConfig() != null
                ? request.embeddingConfig()
                : Map.of("model", "text-embedding-3-small", "dimensions", 1536));
        entity.setEmbeddingModel(request.embeddingModel() != null && !request.embeddingModel().isBlank()
                ? request.embeddingModel() : "BAAI/bge-m3");
        entity.setSyncConfig(request.syncConfig());
        entity.setStatus("idle");
        entity.setCreatedBy(currentUserId()); // CR-022
        return ApiResponse.ok(knowledgeSourceRepository.save(entity));
    }

    @GetMapping("/{id}")
    @Operation(summary = "지식 소스 상세 조회")
    public ApiResponse<KnowledgeSourceEntity> get(@PathVariable String id) {
        return knowledgeSourceRepository.findById(id)
                .map(ApiResponse::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Knowledge source not found: " + id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "지식 소스 수정")
    public ApiResponse<KnowledgeSourceEntity> update(@PathVariable String id,
                                                      @RequestBody KnowledgeSourceRequest request) {
        KnowledgeSourceEntity entity = knowledgeSourceRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Knowledge source not found: " + id));
        if (request.name() != null) entity.setName(request.name());
        if (request.config() != null) entity.setConfig(request.config());
        if (request.chunkingConfig() != null) entity.setChunkingConfig(request.chunkingConfig());
        if (request.embeddingConfig() != null) entity.setEmbeddingConfig(request.embeddingConfig());
        if (request.embeddingModel() != null && !request.embeddingModel().isBlank()) {
            entity.setEmbeddingModel(request.embeddingModel());
        }
        if (request.syncConfig() != null) entity.setSyncConfig(request.syncConfig());
        return ApiResponse.ok(knowledgeSourceRepository.save(entity));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "지식 소스 삭제")
    @org.springframework.transaction.annotation.Transactional
    public void delete(@PathVariable String id) {
        // FK 참조 테이블 먼저 삭제
        ingestionLogRepository.deleteBySourceId(id);
        embeddingRepository.deleteBySourceId(id);
        knowledgeSourceRepository.deleteById(id);
    }

    @PostMapping("/{id}/upload")
    @Operation(summary = "지식 소스 파일 업로드")
    public ApiResponse<Map<String, Object>> uploadFile(
            @PathVariable String id,
            @RequestParam("file") MultipartFile file) {
        KnowledgeSourceEntity entity = knowledgeSourceRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Knowledge source not found: " + id));

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Filename is required");
        }

        String extension = originalFilename.contains(".")
                ? originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase()
                : "";
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unsupported file extension: " + extension
                            + ". Allowed: " + ALLOWED_EXTENSIONS);
        }

        try {
            String tenantId = TenantContext.getTenantId();
            String filePath = storageService.save(tenantId, "knowledge", originalFilename, file.getInputStream());

            // Update entity config with file_path
            Map<String, Object> config = entity.getConfig() != null
                    ? new HashMap<>(entity.getConfig()) : new HashMap<>();
            config.put("file_path", filePath);
            entity.setConfig(config);
            knowledgeSourceRepository.save(entity);

            return ApiResponse.ok(Map.of(
                    "sourceId", id,
                    "filePath", filePath,
                    "fileSize", file.getSize(),
                    "status", "uploaded"
            ));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "File upload failed: " + e.getMessage());
        }
    }

    @PostMapping("/{id}/sync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "지식 소스 동기화")
    public ApiResponse<Map<String, Object>> sync(@PathVariable String id) {
        knowledgeSourceRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Knowledge source not found: " + id));

        ingestionPipeline.ingest(id);

        return ApiResponse.ok(Map.of("sourceId", id, "status", "syncing"));
    }

    @PostMapping("/{id}/ingest-text")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "개별 텍스트 인제스션 (청킹 → 임베딩 → 저장)")
    public ApiResponse<Map<String, Object>> ingestText(
            @PathVariable String id,
            @Valid @RequestBody IngestTextRequest request) {
        KnowledgeSourceEntity source = knowledgeSourceRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Knowledge source not found: " + id));

        if (!mcpRagClient.isAvailable()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "RAG sidecar is not available");
        }

        // upsert: 동일 documentId의 기존 임베딩 삭제 후 재생성
        int deleted = embeddingRepository.deleteBySourceIdAndDocumentId(id, request.documentId());
        if (deleted > 0) {
            log.info("Upsert: deleted {} existing embeddings for document '{}'", deleted, request.documentId());
        }

        String strategy = source.getChunkingConfig() != null
                ? (String) source.getChunkingConfig().getOrDefault("strategy", "semantic")
                : "semantic";

        Map<String, Object> result = mcpRagClient.ingestDocument(
                id, request.content(), request.documentId(),
                strategy, source.getChunkingConfig(), source.getEmbeddingModel());

        boolean success = Boolean.TRUE.equals(result.get("success"));

        if (success) {
            source.setChunkCount(embeddingRepository.countBySourceId(id));
            knowledgeSourceRepository.save(source);
        }

        return ApiResponse.ok(result);
    }

    @DeleteMapping("/{id}/documents/{documentId}")
    @Operation(summary = "개별 문서 임베딩 삭제")
    public ApiResponse<Map<String, Object>> deleteDocument(
            @PathVariable String id,
            @PathVariable String documentId) {
        knowledgeSourceRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Knowledge source not found: " + id));

        int deleted = embeddingRepository.deleteBySourceIdAndDocumentId(id, documentId);

        // chunkCount 갱신
        KnowledgeSourceEntity source = knowledgeSourceRepository.findById(id).get();
        source.setChunkCount(embeddingRepository.countBySourceId(id));
        knowledgeSourceRepository.save(source);

        return ApiResponse.ok(Map.of("sourceId", id, "documentId", documentId, "deletedChunks", deleted));
    }

    @PostMapping("/search")
    @Operation(summary = "지식 검색 (벡터 유사도)")
    public ApiResponse<Map<String, Object>> search(@RequestBody SearchRequest request) {
        int topK = request.topK() != null ? request.topK() : 5;

        // 소스명 조회용 맵 구성
        Map<String, String> sourceNameMap = new HashMap<>();
        List<KnowledgeSourceEntity> allSources = knowledgeSourceRepository.findAll();
        for (KnowledgeSourceEntity src : allSources) {
            sourceNameMap.put(src.getId(), src.getName());
        }

        List<RetrievedChunk> chunks;

        if (request.sourceId() != null && !request.sourceId().isBlank()) {
            // 해당 소스의 embeddingModel 조회하여 같은 모델로 쿼리 임베딩
            String embeddingModel = allSources.stream()
                    .filter(s -> s.getId().equals(request.sourceId()))
                    .map(KnowledgeSourceEntity::getEmbeddingModel)
                    .findFirst().orElse(null);
            chunks = vectorSearcher.search(request.query(), request.sourceId(), topK, embeddingModel);
        } else {
            // sourceId 미지정: 임베딩이 있는 모든 소스에서 검색 후 점수순 병합
            List<RetrievedChunk> merged = new java.util.ArrayList<>();
            for (KnowledgeSourceEntity src : allSources) {
                if (src.getChunkCount() == 0) continue;
                try {
                    merged.addAll(vectorSearcher.search(request.query(), src.getId(), topK, src.getEmbeddingModel()));
                } catch (Exception e) {
                    log.warn("Search failed for source '{}': {}", src.getId(), e.getMessage());
                }
            }
            merged.sort((a, b) -> Double.compare(b.score(), a.score()));
            chunks = merged.stream().limit(topK).toList();
        }

        // 소스명을 포함한 응답 생성
        List<Map<String, Object>> results = chunks.stream().map(c -> {
            Map<String, Object> item = new HashMap<>();
            item.put("content", c.content());
            item.put("score", c.score());
            item.put("sourceId", c.sourceId());
            item.put("sourceName", sourceNameMap.getOrDefault(c.sourceId(), c.sourceId()));
            item.put("metadata", c.metadata());
            return item;
        }).toList();

        return ApiResponse.ok(Map.of("query", request.query(), "results", results));
    }

    @GetMapping("/{id}/ingestion-logs")
    @Operation(summary = "수집 로그 조회")
    public ApiResponse<?> ingestionLogs(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.page(ingestionLogRepository.findBySourceIdOrderByStartedAtDesc(id, PageRequest.of(page, size)));
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record KnowledgeSourceRequest(
            String id,
            @NotBlank String name,
            String description,
            @NotBlank String type,
            Map<String, Object> config,
            Map<String, Object> chunkingConfig,
            Map<String, Object> embeddingConfig,
            Map<String, Object> syncConfig,
            String embeddingModel
    ) {}

    public record IngestTextRequest(
            @NotBlank String content,
            @NotBlank String documentId
    ) {}

    public record SearchRequest(
            @NotBlank String query,
            String sourceId,
            Integer topK
    ) {}

    /** CR-022: SecurityContext에서 현재 사용자 ID 추출. API Key 인증(system-*)은 users FK 없으므로 null 반환 */
    private String currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal up) {
            String id = up.getId();
            if (id != null && id.startsWith("system-")) return null;
            return id;
        }
        return null;
    }
}
