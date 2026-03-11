package com.platform.api;

import com.platform.domain.RetrievalConfigEntity;
import com.platform.repository.RetrievalConfigRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/retrieval-config")
@Tag(name = "Retrieval Config", description = "RAG 검색 설정 관리")
public class RetrievalConfigController {

    private final RetrievalConfigRepository retrievalConfigRepository;

    public RetrievalConfigController(RetrievalConfigRepository retrievalConfigRepository) {
        this.retrievalConfigRepository = retrievalConfigRepository;
    }

    @GetMapping
    @Operation(summary = "검색 설정 목록 조회")
    public ApiResponse<?> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.page(retrievalConfigRepository.findAll(PageRequest.of(page, size)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "검색 설정 생성")
    public ApiResponse<RetrievalConfigEntity> create(@Valid @RequestBody RetrievalConfigRequest request) {
        RetrievalConfigEntity entity = new RetrievalConfigEntity();
        entity.setId(request.id());
        entity.setName(request.name());
        if (request.topK() != null) entity.setTopK(request.topK());
        if (request.similarityThreshold() != null) entity.setSimilarityThreshold(request.similarityThreshold());
        if (request.maxContextTokens() != null) entity.setMaxContextTokens(request.maxContextTokens());
        if (request.searchType() != null) entity.setSearchType(request.searchType());
        entity.setSourceFilters(request.sourceFilters());
        entity.setQueryProcessing(request.queryProcessing());
        entity.setContextTemplate(request.contextTemplate());
        entity.setActive(true);
        return ApiResponse.ok(retrievalConfigRepository.save(entity));
    }

    @GetMapping("/{id}")
    @Operation(summary = "검색 설정 상세 조회")
    public ApiResponse<RetrievalConfigEntity> get(@PathVariable String id) {
        return retrievalConfigRepository.findById(id)
                .map(ApiResponse::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Retrieval config not found: " + id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "검색 설정 수정")
    public ApiResponse<RetrievalConfigEntity> update(@PathVariable String id,
                                                      @Valid @RequestBody RetrievalConfigRequest request) {
        RetrievalConfigEntity entity = retrievalConfigRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Retrieval config not found: " + id));
        entity.setName(request.name());
        if (request.topK() != null) entity.setTopK(request.topK());
        if (request.similarityThreshold() != null) entity.setSimilarityThreshold(request.similarityThreshold());
        if (request.maxContextTokens() != null) entity.setMaxContextTokens(request.maxContextTokens());
        if (request.searchType() != null) entity.setSearchType(request.searchType());
        entity.setSourceFilters(request.sourceFilters());
        entity.setQueryProcessing(request.queryProcessing());
        entity.setContextTemplate(request.contextTemplate());
        return ApiResponse.ok(retrievalConfigRepository.save(entity));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "검색 설정 삭제")
    public void delete(@PathVariable String id) {
        retrievalConfigRepository.deleteById(id);
    }

    public record RetrievalConfigRequest(
            @NotBlank String id,
            @NotBlank String name,
            Integer topK,
            BigDecimal similarityThreshold,
            Integer maxContextTokens,
            String searchType,
            Map<String, Object> sourceFilters,
            Map<String, Object> queryProcessing,
            String contextTemplate
    ) {}
}
