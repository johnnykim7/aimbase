package com.platform.api;

import com.platform.domain.ConversationMemoryEntity;
import com.platform.session.MemoryLayer;
import com.platform.session.MemoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * 메모리 관리 REST API (PRD-131).
 * 계층별 메모리 CRUD, 사용자 프로필 조회.
 */
@RestController
@RequestMapping("/api/v1/memories")
@Tag(name = "Memory", description = "대화 메모리 관리 API")
public class MemoryController {

    private final MemoryService memoryService;

    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
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

    @GetMapping("/profile")
    @Operation(summary = "사용자 프로필 메모리 조회")
    public List<ConversationMemoryEntity> getUserProfile(
            @RequestParam("user_id") String userId) {
        return memoryService.getUserProfile(userId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "메모리 저장")
    public ConversationMemoryEntity create(@RequestBody CreateMemoryRequest request) {
        return memoryService.save(
                request.sessionId(), request.userId(),
                MemoryLayer.valueOf(request.layer()), request.content());
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
            String content
    ) {}
}
