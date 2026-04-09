package com.platform.api;

import com.platform.domain.ConnectionGroupEntity;
import com.platform.domain.ConnectionEntity;
import com.platform.llm.ConnectionAdapterFactory;
import com.platform.llm.ConnectionGroupSelector;
import com.platform.repository.ConnectionGroupRepository;
import com.platform.repository.ConnectionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@RestController
@RequestMapping("/api/v1/connection-groups")
@Tag(name = "Connection Groups", description = "커넥션 그룹 관리 (CR-015)")
public class ConnectionGroupController {

    private final ConnectionGroupRepository groupRepository;
    private final ConnectionRepository connectionRepository;
    private final ConnectionAdapterFactory connectionAdapterFactory;
    private final ConnectionGroupSelector groupSelector;

    public ConnectionGroupController(ConnectionGroupRepository groupRepository,
                                      ConnectionRepository connectionRepository,
                                      ConnectionAdapterFactory connectionAdapterFactory,
                                      ConnectionGroupSelector groupSelector) {
        this.groupRepository = groupRepository;
        this.connectionRepository = connectionRepository;
        this.connectionAdapterFactory = connectionAdapterFactory;
        this.groupSelector = groupSelector;
    }

    @GetMapping
    @Operation(summary = "커넥션 그룹 목록 조회")
    public ApiResponse<?> list(@RequestParam(required = false) String adapter) {
        if (adapter != null) {
            return ApiResponse.ok(groupRepository.findByAdapter(adapter));
        }
        return ApiResponse.ok(groupRepository.findAll());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "커넥션 그룹 생성")
    public ApiResponse<ConnectionGroupEntity> create(@Valid @RequestBody GroupRequest request) {
        validateMembers(request.adapter(), request.members());
        validateDefaultUniqueness(request.adapter(), request.isDefault(), null);

        ConnectionGroupEntity entity = new ConnectionGroupEntity();
        entity.setId(request.id());
        entity.setName(request.name());
        entity.setAdapter(request.adapter());
        entity.setStrategy(request.strategy());
        entity.setMembers(request.members());
        entity.setIsDefault(request.isDefault() != null ? request.isDefault() : false);

        return ApiResponse.ok(groupRepository.save(entity));
    }

    @GetMapping("/{id}")
    @Operation(summary = "커넥션 그룹 상세 조회 (멤버 상태 포함)")
    public ApiResponse<Map<String, Object>> get(@PathVariable String id) {
        ConnectionGroupEntity group = groupRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found: " + id));

        return ApiResponse.ok(enrichGroupWithMemberStatus(group));
    }

    @PutMapping("/{id}")
    @Operation(summary = "커넥션 그룹 수정")
    public ApiResponse<ConnectionGroupEntity> update(@PathVariable String id,
                                                      @Valid @RequestBody GroupRequest request) {
        ConnectionGroupEntity entity = groupRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found: " + id));

        validateMembers(request.adapter(), request.members());
        validateDefaultUniqueness(request.adapter(), request.isDefault(), id);

        entity.setName(request.name());
        entity.setAdapter(request.adapter());
        entity.setStrategy(request.strategy());
        entity.setMembers(request.members());
        if (request.isDefault() != null) {
            entity.setIsDefault(request.isDefault());
        }

        groupSelector.evict(id);
        return ApiResponse.ok(groupRepository.save(entity));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "커넥션 그룹 삭제")
    public void delete(@PathVariable String id) {
        groupSelector.evict(id);
        groupRepository.deleteById(id);
    }

    @PostMapping("/{id}/test")
    @Operation(summary = "그룹 전체 헬스체크")
    public ApiResponse<List<Map<String, Object>>> test(@PathVariable String id) {
        ConnectionGroupEntity group = groupRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found: " + id));

        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> member : group.getMembers()) {
            String connId = (String) member.get("connection_id");
            try {
                var health = connectionAdapterFactory.ping(connId);
                results.add(Map.of("connection_id", connId, "ok", health.ok(), "latencyMs", health.latencyMs()));
            } catch (Exception e) {
                results.add(Map.of("connection_id", connId, "ok", false, "error", e.getMessage()));
            }
        }
        return ApiResponse.ok(results);
    }

    private void validateMembers(String adapter, List<Map<String, Object>> members) {
        if (members == null || members.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Members cannot be empty");
        }
        for (Map<String, Object> member : members) {
            String connId = (String) member.get("connection_id");
            if (connId == null || connId.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Member connection_id is required");
            }
            ConnectionEntity conn = connectionRepository.findById(connId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Connection not found: " + connId));
            if (!conn.getAdapter().equals(adapter)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Connection '" + connId + "' adapter '" + conn.getAdapter()
                                + "' does not match group adapter '" + adapter + "'. Cross-provider groups are not allowed.");
            }
        }
    }

    private void validateDefaultUniqueness(String adapter, Boolean isDefault, String excludeId) {
        if (Boolean.TRUE.equals(isDefault)) {
            groupRepository.findByAdapterAndIsDefaultTrue(adapter).ifPresent(existing -> {
                if (!existing.getId().equals(excludeId)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Default group already exists for adapter '" + adapter + "': " + existing.getId());
                }
            });
        }
    }

    private Map<String, Object> enrichGroupWithMemberStatus(ConnectionGroupEntity group) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", group.getId());
        result.put("name", group.getName());
        result.put("adapter", group.getAdapter());
        result.put("strategy", group.getStrategy());
        result.put("is_default", group.getIsDefault());
        result.put("is_active", group.getIsActive());
        result.put("created_at", group.getCreatedAt());
        result.put("updated_at", group.getUpdatedAt());

        List<Map<String, Object>> enrichedMembers = new ArrayList<>();
        for (Map<String, Object> member : group.getMembers()) {
            Map<String, Object> enriched = new LinkedHashMap<>(member);
            String connId = (String) member.get("connection_id");
            connectionRepository.findById(connId).ifPresent(conn -> {
                enriched.put("connection_name", conn.getName());
                enriched.put("status", conn.getStatus());
            });
            enriched.put("circuit_breaker_state", groupSelector.getCircuitBreakerState(connId));
            enriched.put("usage_count", groupSelector.getUsageCount(connId));
            enrichedMembers.add(enriched);
        }
        result.put("members", enrichedMembers);
        return result;
    }

    public record GroupRequest(
            @NotBlank String id,
            @NotBlank String name,
            @NotBlank String adapter,
            @NotBlank String strategy,
            @NotEmpty List<Map<String, Object>> members,
            Boolean isDefault
    ) {}
}
