package com.platform.api;

import com.platform.domain.AgentRegistryEntity;
import com.platform.service.AgentRegistryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CR-041: 원격 에이전트 레지스트리 REST API.
 * 에이전트가 자가 등록/해제하고, 관리자가 목록을 조회한다.
 */
@RestController
@RequestMapping("/api/v1/agents")
public class AgentRegistryController {

    private final AgentRegistryService agentRegistryService;

    public AgentRegistryController(AgentRegistryService agentRegistryService) {
        this.agentRegistryService = agentRegistryService;
    }

    /**
     * POST /api/v1/agents/register — 에이전트 자가 등록.
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> register(@Valid @RequestBody AgentRegisterRequest request) {
        AgentRegistryEntity entity = agentRegistryService.register(
                request.agentName(), request.publicAddress(), request.mcpPort(),
                request.toolNames(), request.metadata());
        return Map.of("data", entityToMap(entity));
    }

    /**
     * DELETE /api/v1/agents/{id} — 에이전트 해제.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deregister(@PathVariable UUID id) {
        agentRegistryService.deregister(id);
    }

    /**
     * GET /api/v1/agents — 에이전트 목록 조회.
     */
    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "ACTIVE") String status) {
        List<AgentRegistryEntity> agents = agentRegistryService.listByStatus(status);
        return Map.of("data", agents.stream().map(this::entityToMap).toList());
    }

    /**
     * POST /api/v1/agents/{id}/heartbeat — 하트비트.
     */
    @PostMapping("/{id}/heartbeat")
    public Map<String, Object> heartbeat(@PathVariable UUID id) {
        agentRegistryService.heartbeat(id);
        return Map.of("status", "ok");
    }

    private Map<String, Object> entityToMap(AgentRegistryEntity e) {
        var map = new java.util.LinkedHashMap<String, Object>();
        map.put("id", e.getId());
        map.put("agentName", e.getAgentName());
        map.put("publicAddress", e.getPublicAddress());
        map.put("mcpPort", e.getMcpPort());
        map.put("turnRelayAddress", e.getTurnRelayAddress());
        map.put("status", e.getStatus());
        map.put("toolsCache", e.getToolsCache());
        map.put("registeredAt", e.getRegisteredAt().toString());
        map.put("lastHeartbeatAt", e.getLastHeartbeatAt().toString());
        return map;
    }

    /**
     * 에이전트 등록 요청 DTO.
     */
    public record AgentRegisterRequest(
            @NotBlank String agentName,
            @NotBlank String publicAddress,
            @NotNull Integer mcpPort,
            List<String> toolNames,
            Map<String, Object> metadata
    ) {}
}
