package com.platform.session;

import com.platform.domain.ResponseCacheEntity;
import com.platform.rag.EmbeddingService;
import com.platform.repository.ResponseCacheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 응답 캐시 서비스 (PRD-128: Exact Match, PRD-129: Semantic Match).
 *
 * Phase 1 (PRD-128): SHA-256 해시 기반 Exact Match.
 * Phase 2 (PRD-129): pgvector 코사인 유사도 기반 Semantic Match.
 */
@Service
public class ResponseCacheService {

    private static final Logger log = LoggerFactory.getLogger(ResponseCacheService.class);
    private static final int DEFAULT_TTL_MINUTES = 60;
    private static final double SEMANTIC_THRESHOLD = 0.95;

    private final ResponseCacheRepository cacheRepository;
    private final EmbeddingService embeddingService;

    public ResponseCacheService(ResponseCacheRepository cacheRepository, EmbeddingService embeddingService) {
        this.cacheRepository = cacheRepository;
        this.embeddingService = embeddingService;
    }

    /**
     * 캐시 조회: Exact Match → Semantic Match 순서로 시도.
     */
    public Optional<String> get(String model, String systemPrompt, String userMessage) {
        // 1차: Exact Match
        String key = buildCacheKey(model, systemPrompt, userMessage);
        Optional<String> exact = cacheRepository.findByCacheKeyAndExpiresAtAfter(key, OffsetDateTime.now())
                .map(entity -> {
                    entity.setHitCount(entity.getHitCount() + 1);
                    cacheRepository.save(entity);
                    log.debug("Exact 캐시 히트: key={}, hits={}", key.substring(0, 12), entity.getHitCount());
                    return entity.getResponseText();
                });
        if (exact.isPresent()) return exact;

        // 2차: Semantic Match (PRD-129)
        return getSemanticMatch(userMessage);
    }

    /**
     * PRD-129: Semantic Match — 의미적으로 유사한 질문의 캐시 응답 반환.
     */
    private Optional<String> getSemanticMatch(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return Optional.empty();
        try {
            float[] embedding = embeddingService.embed(userMessage);
            String embeddingStr = embeddingService.vectorToString(embedding);
            return cacheRepository.findSemanticallySimlar(embeddingStr, SEMANTIC_THRESHOLD)
                    .map(entity -> {
                        entity.setHitCount(entity.getHitCount() + 1);
                        cacheRepository.save(entity);
                        log.info("Semantic 캐시 히트: 원본='{}', 캐시='{}'",
                                truncate(userMessage, 30), truncate(entity.getUserMessage(), 30));
                        return entity.getResponseText();
                    });
        } catch (Exception e) {
            log.debug("Semantic 캐시 조회 실패 (무시): {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 캐시 저장 (Exact + Semantic 임베딩 동시 저장).
     */
    public void put(String model, String systemPrompt, String userMessage,
                    String responseText, Integer tokenCount) {
        String key = buildCacheKey(model, systemPrompt, userMessage);

        ResponseCacheEntity entity = new ResponseCacheEntity();
        entity.setCacheKey(key);
        entity.setModel(model);
        entity.setUserMessage(userMessage);
        entity.setResponseText(responseText);
        entity.setTokenCount(tokenCount);
        entity.setExpiresAt(OffsetDateTime.now().plusMinutes(DEFAULT_TTL_MINUTES));

        cacheRepository.save(entity);

        // PRD-129: 임베딩 비동기 저장 (Semantic Match용)
        Thread.ofVirtual().start(() -> {
            try {
                float[] embedding = embeddingService.embed(userMessage);
                String embeddingStr = embeddingService.vectorToString(embedding);
                cacheRepository.updateEmbedding(entity.getId(), embeddingStr);
                log.debug("캐시 임베딩 저장: key={}", key.substring(0, 12));
            } catch (Exception e) {
                log.debug("캐시 임베딩 저장 실패 (무시): {}", e.getMessage());
            }
        });

        log.debug("캐시 저장: key={}, model={}", key.substring(0, 12), model);
    }

    /** 만료된 캐시 정리 */
    public int evictExpired() {
        return cacheRepository.deleteExpired(OffsetDateTime.now());
    }

    /** 캐시 통계 조회 */
    public java.util.Map<String, Object> getStats() {
        long totalCount = cacheRepository.count();
        long totalHits = cacheRepository.sumHitCount();
        return java.util.Map.of(
                "totalEntries", totalCount,
                "totalHits", totalHits
        );
    }

    private String buildCacheKey(String model, String systemPrompt, String userMessage) {
        String raw = (model != null ? model : "") + "|"
                + (systemPrompt != null ? sha256(systemPrompt) : "") + "|"
                + (userMessage != null ? userMessage : "");
        return sha256(raw);
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 해시 실패", e);
        }
    }
}
