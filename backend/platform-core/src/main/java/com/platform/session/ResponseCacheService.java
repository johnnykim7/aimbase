package com.platform.session;

import com.platform.domain.ResponseCacheEntity;
import com.platform.rag.EmbeddingService;
import com.platform.repository.ResponseCacheRepository;
import com.platform.tenant.TenantContext;
import com.platform.tenant.TenantDataSourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final TenantDataSourceManager tenantDataSourceManager;
    private final TransactionTemplate transactionTemplate;

    public ResponseCacheService(ResponseCacheRepository cacheRepository,
                                 EmbeddingService embeddingService,
                                 TenantDataSourceManager tenantDataSourceManager,
                                 TransactionTemplate transactionTemplate) {
        this.cacheRepository = cacheRepository;
        this.embeddingService = embeddingService;
        this.tenantDataSourceManager = tenantDataSourceManager;
        this.transactionTemplate = transactionTemplate;
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

        // ⚠️ 반드시 만료분까지 포함해 찾는다.
        // 조회(get)는 findByCacheKeyAndExpiresAtAfter 로 "안 만료된 것"만 보는데,
        // cache_key 에는 UNIQUE(idx_response_cache_key)가 걸려 있다. 그래서 TTL(60분)이 지난
        // 행이 남아 있으면 조회는 미스 → 저장은 새 행 insert → duplicate key 로 터졌다.
        // 만료행을 지우는 deleteExpired 는 호출하는 스케줄러가 없어 계속 쌓인다.
        // 결국 "같은 질문을 60분 뒤에 다시 하면 캐시가 깨지는" 상태였다.
        ResponseCacheEntity found = cacheRepository.findByCacheKey(key)
                .orElseGet(ResponseCacheEntity::new);
        found.setCacheKey(key);
        found.setModel(model);
        found.setUserMessage(userMessage);
        found.setResponseText(responseText);
        found.setTokenCount(tokenCount);
        found.setExpiresAt(OffsetDateTime.now().plusMinutes(DEFAULT_TTL_MINUTES));

        final ResponseCacheEntity entity = cacheRepository.save(found);

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

    /**
     * 매시 30분, 만료 캐시 삭제.
     *
     * <p>TTL 이 60분인데 이 정리를 부르는 곳이 아무 데도 없어서 만료 행이 무한정 쌓였다.
     * {@code cache_key} 는 UNIQUE 라, 쌓인 만료 행이 곧바로 저장 충돌로 이어졌다
     * (조회는 만료분을 건너뛰고 저장은 새 행을 넣으려 해서 duplicate key). 저장 쪽은
     * {@link #put} 에서 upsert 로 막았지만, 안 쓰는 행이 계속 남는 것은 그대로라 여기서 지운다.
     *
     * <p>{@code response_cache} 는 테넌트 테이블이므로 테넌트별로 순회한다 — CR-122 가
     * {@code tool_result_storage} 에서 겪은 것과 같은 함정이다. {@link TenantContext} 없이 부르면
     * master DB 로 라우팅되는데 master 엔 이 테이블이 없어 매번 조용히 실패한다.
     *
     * <p>순회 메서드에는 {@code @Transactional} 을 걸지 않는다 — 트랜잭션이 첫 테넌트 커넥션에
     * 묶이면 이후 테넌트 라우팅이 어긋난다. 테넌트별 삭제만 {@link TransactionTemplate} 으로 감싼다.
     *
     * <p>순회 대상은 DataSource 가 이미 만들어진 테넌트뿐이다(CR-122 와 동일). 기동 후 한 번도
     * 트래픽이 없던 테넌트는 캐시에 쌓인 것도 없으므로 문제되지 않는다.
     */
    @Scheduled(cron = "0 30 * * * *")
    public void purgeExpired() {
        for (String tenantId : tenantDataSourceManager.getAllCachedDataSources().keySet()) {
            try {
                TenantContext.setTenantId(tenantId);
                Integer removed = transactionTemplate.execute(
                        status -> cacheRepository.deleteExpired(OffsetDateTime.now()));
                if (removed != null && removed > 0) {
                    log.info("만료 응답캐시 {}건 삭제 (tenant={})", removed, tenantId);
                }
            } catch (Exception e) {
                log.warn("응답캐시 정리 실패 tenant={}: {}", tenantId, e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }

    /** 만료된 캐시 정리 (수동 호출용 — 현재 테넌트 컨텍스트 기준) */
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
