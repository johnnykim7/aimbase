package com.platform.session;

import com.platform.domain.ResponseCacheEntity;
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
 * Phase 2 (PRD-129): pgvector 코사인 유사도 기반 Semantic Match (확장점).
 */
@Service
public class ResponseCacheService {

    private static final Logger log = LoggerFactory.getLogger(ResponseCacheService.class);
    private static final int DEFAULT_TTL_MINUTES = 60;

    private final ResponseCacheRepository cacheRepository;

    public ResponseCacheService(ResponseCacheRepository cacheRepository) {
        this.cacheRepository = cacheRepository;
    }

    /**
     * Exact Match 캐시 조회.
     * SHA-256(model + systemPromptHash + userMessage)로 조회.
     */
    public Optional<String> get(String model, String systemPrompt, String userMessage) {
        String key = buildCacheKey(model, systemPrompt, userMessage);
        return cacheRepository.findByCacheKeyAndExpiresAtAfter(key, OffsetDateTime.now())
                .map(entity -> {
                    entity.setHitCount(entity.getHitCount() + 1);
                    cacheRepository.save(entity);
                    log.debug("캐시 히트: key={}, hits={}", key.substring(0, 12), entity.getHitCount());
                    return entity.getResponseText();
                });
    }

    /**
     * 캐시 저장.
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
        log.debug("캐시 저장: key={}, model={}", key.substring(0, 12), model);
    }

    /** 만료된 캐시 정리 */
    public int evictExpired() {
        return cacheRepository.deleteExpired(OffsetDateTime.now());
    }

    private String buildCacheKey(String model, String systemPrompt, String userMessage) {
        String raw = (model != null ? model : "") + "|"
                + (systemPrompt != null ? sha256(systemPrompt) : "") + "|"
                + (userMessage != null ? userMessage : "");
        return sha256(raw);
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
