package com.platform.api;

import com.platform.domain.master.ClaudeCodeErrorPatternEntity;
import com.platform.repository.master.ClaudeCodeErrorPatternRepository;
import com.platform.tool.builtin.ClaudeCodeCircuitBreaker;
import com.platform.tool.builtin.ErrorClassificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ClaudeCodeTool 관리 API (Platform/SuperAdmin 전용, Master DB).
 * 에러 패턴 CRUD + 서킷 브레이커 상태 조회/리셋.
 */
@RestController
@RequestMapping("/api/v1/platform/claude-code")
@Tag(name = "Claude Code Admin", description = "ClaudeCodeTool 에러 패턴 및 서킷 브레이커 관리")
@ConditionalOnProperty(name = "claude-code.enabled", havingValue = "true", matchIfMissing = false)
public class ClaudeCodePlatformController {

    private final ClaudeCodeErrorPatternRepository patternRepository;
    private final ErrorClassificationService errorClassificationService;
    private final ClaudeCodeCircuitBreaker circuitBreaker;

    public ClaudeCodePlatformController(ClaudeCodeErrorPatternRepository patternRepository,
                                         ErrorClassificationService errorClassificationService,
                                         ClaudeCodeCircuitBreaker circuitBreaker) {
        this.patternRepository = patternRepository;
        this.errorClassificationService = errorClassificationService;
        this.circuitBreaker = circuitBreaker;
    }

    // ── 에러 패턴 관리 ──

    @GetMapping("/error-patterns")
    @Operation(summary = "에러 패턴 목록 조회")
    public List<ClaudeCodeErrorPatternEntity> listErrorPatterns() {
        return patternRepository.findByIsActiveTrueOrderByPriorityDesc();
    }

    @PostMapping("/error-patterns")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "에러 패턴 등록")
    public ClaudeCodeErrorPatternEntity createErrorPattern(@RequestBody ClaudeCodeErrorPatternEntity entity) {
        ClaudeCodeErrorPatternEntity saved = patternRepository.save(entity);
        errorClassificationService.refreshPatterns();
        return saved;
    }

    @DeleteMapping("/error-patterns/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "에러 패턴 삭제")
    public void deleteErrorPattern(@PathVariable Long id) {
        if (!patternRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "패턴을 찾을 수 없습니다: " + id);
        }
        patternRepository.deleteById(id);
        errorClassificationService.refreshPatterns();
    }

    // ── 서킷 브레이커 ──

    @GetMapping("/circuit-status")
    @Operation(summary = "서킷 브레이커 상태 조회")
    public Map<String, Object> getCircuitStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("state", circuitBreaker.getState().name());
        status.put("consecutive_failures", circuitBreaker.getConsecutiveFailures());
        status.put("last_failure_at", circuitBreaker.getLastFailureAt());
        status.put("last_success_at", circuitBreaker.getLastSuccessAt());
        status.put("open_until", circuitBreaker.getOpenUntil());
        return status;
    }

    @PostMapping("/circuit-reset")
    @Operation(summary = "서킷 브레이커 수동 리셋 (OPEN → CLOSED)")
    public Map<String, Object> resetCircuit() {
        circuitBreaker.reset();
        return Map.of("state", circuitBreaker.getState().name(), "message", "서킷 리셋 완료");
    }
}
