package com.platform.api;

import com.platform.domain.ConversationMemoryEntity;
import com.platform.session.MemoryLayer;
import com.platform.session.MemoryScope;
import com.platform.session.MemoryService;
import com.platform.session.TeamMemoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * 메모리 관리 REST API (PRD-131, PRD-201).
 * 계층별 메모리 CRUD, 사용자 프로필 조회, 팀/글로벌 메모리 관리.
 */
@RestController
@RequestMapping("/api/v1/memories")
@Tag(name = "Memory", description = "대화 메모리 관리 API")
public class MemoryController {

    private final MemoryService memoryService;
    private final TeamMemoryService teamMemoryService;

    public MemoryController(MemoryService memoryService, TeamMemoryService teamMemoryService) {
        this.memoryService = memoryService;
        this.teamMemoryService = teamMemoryService;
    }

    @GetMapping
    @Operation(summary = "세션별 메모리 조회")
    public List<ConversationMemoryEntity> listBySession(
            @RequestParam("session_id") String sessionId,
            @RequestParam(value = "layer", required = false) String layer) {
        if (layer != null && !layer.isBlank()) {
            return memoryService.getBySessionAndLayer(sessionId, MemoryLayer.valueOf(layer));
        }
        return memoryService.getAllBySession(sessionId);
    }

    @GetMapping("/team")
    @Operation(summary = "팀 메모리 조회 (PRD-201)")
    public List<ConversationMemoryEntity> listByTeam(
            @RequestParam("team_id") String teamId,
            @RequestParam(value = "layer", required = false) String layer) {
        if (layer != null && !layer.isBlank()) {
            return teamMemoryService.getByTeamAndLayer(teamId, MemoryLayer.valueOf(layer));
        }
        return teamMemoryService.getAllByTeam(teamId);
    }

    @GetMapping("/global")
    @Operation(summary = "글로벌 메모리 조회 (PRD-199)")
    public List<ConversationMemoryEntity> listGlobal(
            @RequestParam(value = "layer", required = false) String layer) {
        if (layer != null && !layer.isBlank()) {
            return memoryService.getGlobalMemory(MemoryLayer.valueOf(layer));
        }
        return memoryService.getGlobalMemory(MemoryLayer.SYSTEM_RULES);
    }

    @GetMapping("/profile")
    @Operation(summary = "사용자 프로필 메모리 조회")
    public List<ConversationMemoryEntity> getUserProfile(
            @RequestParam("user_id") String userId) {
        return memoryService.getUserProfile(userId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "메모리 저장 (scope 지정 가능)")
    public ConversationMemoryEntity create(@RequestBody CreateMemoryRequest request) {
        MemoryScope scope = request.scope() != null
                ? MemoryScope.valueOf(request.scope())
                : MemoryScope.PRIVATE;

        if (scope == MemoryScope.TEAM) {
            return teamMemoryService.save(
                    request.teamId(), request.userId(),
                    MemoryLayer.valueOf(request.layer()), request.content());
        }

        return memoryService.save(
                request.sessionId(), request.userId(),
                MemoryLayer.valueOf(request.layer()), request.content(),
                scope, request.teamId());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "메모리 삭제")
    public void delete(@PathVariable UUID id) {
        memoryService.delete(id);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "세션 메모리 전체 삭제")
    public void deleteBySession(@RequestParam("session_id") String sessionId) {
        memoryService.deleteBySession(sessionId);
    }

    public record CreateMemoryRequest(
            String sessionId,
            String userId,
            String layer,
            String content,
            String scope,
            String teamId
    ) {}
}
