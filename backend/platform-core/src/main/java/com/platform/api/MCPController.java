package com.platform.api;

import com.platform.domain.MCPServerEntity;
import com.platform.llm.model.UnifiedToolDef;
import com.platform.mcp.MCPServerManager;
import com.platform.repository.MCPServerRepository;
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
@RequestMapping("/api/v1/mcp-servers")
@Tag(name = "MCP Servers", description = "MCP 서버 관리")
public class MCPController {

    private final MCPServerRepository mcpServerRepository;
    private final MCPServerManager mcpServerManager;

    public MCPController(MCPServerRepository mcpServerRepository,
                          MCPServerManager mcpServerManager) {
        this.mcpServerRepository = mcpServerRepository;
        this.mcpServerManager = mcpServerManager;
    }

    @GetMapping
    @Operation(summary = "MCP 서버 목록 조회")
    public ApiResponse<?> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.page(mcpServerRepository.findAll(PageRequest.of(page, size)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "MCP 서버 등록")
    public ApiResponse<MCPServerEntity> create(@Valid @RequestBody MCPServerRequest request) {
        MCPServerEntity entity = new MCPServerEntity();
        entity.setId(request.id() != null && !request.id().isBlank() ? request.id() : java.util.UUID.randomUUID().toString());
        entity.setName(request.name());
        entity.setTransport(request.transport());
        entity.setConfig(request.config());
        entity.setAutoStart(request.autoStart() != null ? request.autoStart() : true);
        entity.setStatus("disconnected");
        return ApiResponse.ok(mcpServerRepository.save(entity));
    }

    @GetMapping("/{id}")
    @Operation(summary = "MCP 서버 상세 조회")
    public ApiResponse<MCPServerEntity> get(@PathVariable String id) {
        return mcpServerRepository.findById(id)
                .map(ApiResponse::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "MCP server not found: " + id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "MCP 서버 수정")
    public ApiResponse<MCPServerEntity> update(@PathVariable String id,
                                                @Valid @RequestBody MCPServerRequest request) {
        MCPServerEntity entity = mcpServerRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "MCP server not found: " + id));
        entity.setName(request.name());
        entity.setConfig(request.config());
        if (request.autoStart() != null) entity.setAutoStart(request.autoStart());
        return ApiResponse.ok(mcpServerRepository.save(entity));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "MCP 서버 삭제")
    public void delete(@PathVariable String id) {
        mcpServerRepository.deleteById(id);
    }

    @PostMapping("/{id}/discover")
    @Operation(summary = "MCP 서버 도구 목록 탐색 및 등록")
    public ApiResponse<Map<String, Object>> discover(@PathVariable String id) {
        mcpServerRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "MCP server not found: " + id));
        try {
            List<UnifiedToolDef> tools = mcpServerManager.discover(id);
            return ApiResponse.ok(Map.of(
                    "serverId", id,
                    "toolCount", tools.size(),
                    "tools", tools
            ));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Failed to discover tools from MCP server '" + id + "': " + e.getMessage());
        }
    }

    @PostMapping("/{id}/disconnect")
    @Operation(summary = "MCP 서버 연결 해제")
    public ApiResponse<Map<String, String>> disconnect(@PathVariable String id) {
        mcpServerRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "MCP server not found: " + id));
        mcpServerManager.disconnect(id);
        return ApiResponse.ok(Map.of("serverId", id, "status", "disconnected"));
    }

    public record MCPServerRequest(
            String id,
            @NotBlank String name,
            @NotBlank String transport,
            @NotNull Map<String, Object> config,
            Boolean autoStart
    ) {}
}
