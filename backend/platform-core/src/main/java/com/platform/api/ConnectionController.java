package com.platform.api;

import com.platform.action.AdapterRegistry;
import com.platform.action.model.HealthStatus;
import com.platform.domain.ConnectionEntity;
import com.platform.llm.ConnectionAdapterFactory;
import com.platform.repository.ConnectionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/connections")
@Tag(name = "Connections", description = "외부 시스템 연결 관리")
public class ConnectionController {

    private final ConnectionRepository connectionRepository;
    private final AdapterRegistry adapterRegistry;
    private final ConnectionAdapterFactory connectionAdapterFactory;

    public ConnectionController(ConnectionRepository connectionRepository,
                                 AdapterRegistry adapterRegistry,
                                 ConnectionAdapterFactory connectionAdapterFactory) {
        this.connectionRepository = connectionRepository;
        this.adapterRegistry = adapterRegistry;
        this.connectionAdapterFactory = connectionAdapterFactory;
    }

    @GetMapping
    @Operation(summary = "연결 목록 조회")
    public ApiResponse<?> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String type
    ) {
        var pageable = PageRequest.of(page, size);
        if (type != null) {
            return ApiResponse.ok(connectionRepository.findByType(type));
        }
        return ApiResponse.page(connectionRepository.findAll(pageable));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "연결 생성")
    public ApiResponse<ConnectionEntity> create(@Valid @RequestBody ConnectionRequest request) {
        ConnectionEntity entity = new ConnectionEntity();
        entity.setId(request.id());
        entity.setName(request.name());
        entity.setAdapter(request.adapter());
        entity.setType(request.type());
        entity.setConfig(request.config());
        entity.setStatus("disconnected");
        return ApiResponse.ok(connectionRepository.save(entity));
    }

    @GetMapping("/{id}")
    @Operation(summary = "연결 상세 조회")
    public ApiResponse<ConnectionEntity> get(@PathVariable String id) {
        return connectionRepository.findById(id)
                .map(ApiResponse::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Connection not found: " + id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "연결 수정")
    public ApiResponse<ConnectionEntity> update(@PathVariable String id,
                                                  @Valid @RequestBody ConnectionRequest request) {
        ConnectionEntity entity = connectionRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Connection not found: " + id));
        entity.setName(request.name());
        entity.setConfig(request.config());
        connectionAdapterFactory.evict(id);  // API Key 변경 시 캐시 무효화
        return ApiResponse.ok(connectionRepository.save(entity));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "연결 삭제")
    public void delete(@PathVariable String id) {
        connectionRepository.deleteById(id);
    }

    @PostMapping("/{id}/test")
    @Operation(summary = "연결 테스트")
    public ApiResponse<Map<String, Object>> test(@PathVariable String id) {
        ConnectionEntity entity = connectionRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Connection not found: " + id));

        try {
            HealthStatus health;
            if ("llm".equals(entity.getType())) {
                // CR-008: LLM 연결은 실제 API 호출로 검증
                health = connectionAdapterFactory.ping(id);
            } else if ("write".equals(entity.getType()) && adapterRegistry.hasWriteAdapter(entity.getAdapter())) {
                health = adapterRegistry.getWriteAdapter(entity.getAdapter()).healthCheck();
            } else if ("notify".equals(entity.getType()) && adapterRegistry.hasNotifyAdapter(entity.getAdapter())) {
                health = adapterRegistry.getNotifyAdapter(entity.getAdapter()).healthCheck();
            } else {
                health = new HealthStatus(true, 0);
            }
            entity.setStatus(health.ok() ? "connected" : "error");
            connectionRepository.save(entity);
            return ApiResponse.ok(Map.of("ok", health.ok(), "latencyMs", health.latencyMs()));
        } catch (Exception e) {
            entity.setStatus("error");
            connectionRepository.save(entity);
            return ApiResponse.error(e.getMessage());
        }
    }

    public record ConnectionRequest(
            @NotBlank String id,
            @NotBlank String name,
            @NotBlank String adapter,
            @NotBlank String type,
            Map<String, Object> config
    ) {}
}
