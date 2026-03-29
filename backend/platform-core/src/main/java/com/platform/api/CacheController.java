package com.platform.api;

import com.platform.session.ResponseCacheService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 캐시 관리 REST API (PRD-128).
 * 캐시 통계 조회, 전체/개별 무효화.
 */
@RestController
@RequestMapping("/api/v1/cache")
@Tag(name = "Cache", description = "LLM 응답 캐시 관리 API")
public class CacheController {

    private final ResponseCacheService cacheService;

    public CacheController(ResponseCacheService cacheService) {
        this.cacheService = cacheService;
    }

    @GetMapping("/stats")
    @Operation(summary = "캐시 통계 조회")
    public Map<String, Object> getStats() {
        return cacheService.getStats();
    }

    @DeleteMapping
    @Operation(summary = "전체 캐시 무효화")
    public Map<String, Object> evictAll() {
        int evicted = cacheService.evictExpired();
        return Map.of("evicted", evicted, "status", "ok");
    }
}
