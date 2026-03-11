package com.platform.api;

import com.platform.domain.RoutingConfigEntity;
import com.platform.repository.RoutingConfigRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/routing")
@Tag(name = "Routing", description = "LLM 라우팅 설정 관리")
public class RoutingController {

    private final RoutingConfigRepository routingConfigRepository;

    public RoutingController(RoutingConfigRepository routingConfigRepository) {
        this.routingConfigRepository = routingConfigRepository;
    }

    @GetMapping
    @Operation(summary = "라우팅 설정 목록 조회")
    public ApiResponse<?> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.page(routingConfigRepository.findAll(PageRequest.of(page, size)));
    }

    @GetMapping("/active")
    @Operation(summary = "활성 라우팅 설정 조회")
    public ApiResponse<List<RoutingConfigEntity>> active() {
        return ApiResponse.ok(routingConfigRepository.findByIsActiveTrue());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "라우팅 설정 생성")
    public ApiResponse<RoutingConfigEntity> create(@Valid @RequestBody RoutingRequest request) {
        RoutingConfigEntity entity = new RoutingConfigEntity();
        entity.setId(request.id());
        entity.setStrategy(request.strategy());
        entity.setRules(request.rules());
        entity.setFallbackChain(request.fallbackChain());
        entity.setActive(true);
        return ApiResponse.ok(routingConfigRepository.save(entity));
    }

    @GetMapping("/{id}")
    @Operation(summary = "라우팅 설정 상세 조회")
    public ApiResponse<RoutingConfigEntity> get(@PathVariable String id) {
        return routingConfigRepository.findById(id)
                .map(ApiResponse::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Routing config not found: " + id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "라우팅 설정 수정")
    public ApiResponse<RoutingConfigEntity> update(@PathVariable String id,
                                                    @Valid @RequestBody RoutingRequest request) {
        RoutingConfigEntity entity = routingConfigRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Routing config not found: " + id));
        entity.setStrategy(request.strategy());
        entity.setRules(request.rules());
        entity.setFallbackChain(request.fallbackChain());
        return ApiResponse.ok(routingConfigRepository.save(entity));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "라우팅 설정 삭제")
    public void delete(@PathVariable String id) {
        routingConfigRepository.deleteById(id);
    }

    public record RoutingRequest(
            @NotBlank String id,
            @NotBlank String strategy,
            @NotNull List<Map<String, Object>> rules,
            List<String> fallbackChain
    ) {}
}
