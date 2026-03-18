package com.platform.api;

import com.platform.domain.KnowledgeSourceEntity;
import com.platform.rag.IngestionPipeline;
import com.platform.rag.VectorSearcher;
import com.platform.rag.model.RetrievedChunk;
import com.platform.repository.IngestionLogRepository;
import com.platform.repository.KnowledgeSourceRepository;
import com.platform.storage.StorageService;
import com.platform.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/knowledge-sources")
@Tag(name = "Knowledge Sources", description = "RAG 지식 소스 관리")
public class KnowledgeController {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "pdf", "docx", "xlsx", "pptx", "csv", "txt", "md", "html"
    );

    private final KnowledgeSourceRepository knowledgeSourceRepository;
    private final IngestionLogRepository ingestionLogRepository;
    private final IngestionPipeline ingestionPipeline;
    private final VectorSearcher vectorSearcher;
    private final StorageService storageService;

    public KnowledgeController(KnowledgeSourceRepository knowledgeSourceRepository,
                                IngestionLogRepository ingestionLogRepository,
                                IngestionPipeline ingestionPipeline,
                                VectorSearcher vectorSearcher,
                                StorageService storageService) {
        this.knowledgeSourceRepository = knowledgeSourceRepository;
        this.ingestionLogRepository = ingestionLogRepository;
        this.ingestionPipeline = ingestionPipeline;
        this.vectorSearcher = vectorSearcher;
        this.storageService = storageService;
    }

    @GetMapping
    @Operation(summary = "지식 소스 목록 조회")
    public ApiResponse<?> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String type
    ) {
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
        entity.setId(request.id());
        entity.setName(request.name());
        entity.setType(request.type());
        entity.setConfig(request.config());
        entity.setChunkingConfig(request.chunkingConfig() != null
                ? request.chunkingConfig()
                : Map.of("strategy", "fixed", "size", 512, "overlap", 50));
        entity.setEmbeddingConfig(request.embeddingConfig() != null
                ? request.embeddingConfig()
                : Map.of("model", "text-embedding-3-small", "dimensions", 1536));
        entity.setSyncConfig(request.syncConfig());
        entity.setStatus("idle");
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
                                                      @Valid @RequestBody KnowledgeSourceRequest request) {
        KnowledgeSourceEntity entity = knowledgeSourceRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Knowledge source not found: " + id));
        entity.setName(request.name());
        entity.setConfig(request.config());
        if (request.chunkingConfig() != null) entity.setChunkingConfig(request.chunkingConfig());
        if (request.embeddingConfig() != null) entity.setEmbeddingConfig(request.embeddingConfig());
        entity.setSyncConfig(request.syncConfig());
        return ApiResponse.ok(knowledgeSourceRepository.save(entity));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "지식 소스 삭제")
    public void delete(@PathVariable String id) {
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

    @PostMapping("/search")
    @Operation(summary = "지식 검색 (벡터 유사도)")
    public ApiResponse<Map<String, Object>> search(@RequestBody SearchRequest request) {
        int topK = request.topK() != null ? request.topK() : 5;
        List<RetrievedChunk> results = vectorSearcher.search(
                request.query(), request.sourceId(), topK);
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

    public record KnowledgeSourceRequest(
            @NotBlank String id,
            @NotBlank String name,
            @NotBlank String type,
            @NotNull Map<String, Object> config,
            Map<String, Object> chunkingConfig,
            Map<String, Object> embeddingConfig,
            Map<String, Object> syncConfig
    ) {}

    public record SearchRequest(
            @NotBlank String query,
            String sourceId,
            Integer topK
    ) {}
}
