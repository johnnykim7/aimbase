package com.platform.service;

import com.platform.domain.PromptTemplateEntity;
import com.platform.repository.PromptTemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * CR-036 PRD-250: 프롬프트 템플릿 서비스.
 * 3단계 폴백: 로컬 캐시 → DB → resources/prompts/*.txt
 */
@Service
public class PromptTemplateService {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateService.class);
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L; // 5분
    private static final Pattern VAR_PATTERN = Pattern.compile("\\{\\{(\\w+)}}");

    /** CR-049 BIZ-098: cascade 합산 길이 경고 임계 (8 KB). */
    public static final int CASCADE_WARN_BYTES = 8 * 1024;

    private final PromptTemplateRepository repository;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public PromptTemplateService(PromptTemplateRepository repository) {
        this.repository = repository;
    }

    /**
     * 프롬프트 템플릿 조회 (캐시 → DB → 파일 폴백).
     */
    public String getTemplate(String key) {
        CacheEntry entry = cache.get(key);
        if (entry != null && !entry.isExpired()) {
            return entry.value;
        }

        try {
            Optional<PromptTemplateEntity> entity = repository.findActiveByKey(key);
            if (entity.isPresent()) {
                String template = entity.get().getTemplate();
                cache.put(key, new CacheEntry(template));
                return template;
            }
        } catch (Exception e) {
            log.warn("DB lookup failed for prompt template '{}': {}", key, e.getMessage());
        }

        return loadFromFile(key);
    }

    /**
     * 프롬프트 렌더링 (변수 치환).
     */
    public String render(String key, Map<String, Object> variables) {
        String template = getTemplate(key);
        if (template == null) return null;
        return renderTemplate(template, variables);
    }

    /**
     * CR-049 PRD-305 / BIZ-098: scope cascade append 로 템플릿을 병합해 반환.
     * 우선순위는 GLOBAL base + TENANT append + PROJECT append 이며 replace 가 아닌 누적.
     * tenant DB 이므로 tenant_id 는 자동 스코프, projectId 가 null 이면 PROJECT 단계 생략.
     */
    public CascadeResult getMergedTemplate(String key, String projectId) {
        String global = firstActive(key, "GLOBAL", null);
        String tenant = firstActive(key, "TENANT", null);
        String project = projectId != null ? firstActive(key, "PROJECT", projectId) : null;

        StringBuilder merged = new StringBuilder();
        if (global != null) merged.append(global);
        if (tenant != null) {
            if (merged.length() > 0) merged.append("\n\n");
            merged.append(tenant);
        }
        if (project != null) {
            if (merged.length() > 0) merged.append("\n\n");
            merged.append(project);
        }
        int lengthBytes = merged.toString().getBytes(StandardCharsets.UTF_8).length;
        String warning = null;
        if (lengthBytes > CASCADE_WARN_BYTES) {
            warning = "cascade 합산 길이 " + lengthBytes + " bytes (권장 상한 " + CASCADE_WARN_BYTES + ")";
            log.warn("prompt cascade overflow: key={} projectId={} bytes={}", key, projectId, lengthBytes);
        }
        return new CascadeResult(
                global,
                tenant,
                project,
                merged.length() == 0 ? null : merged.toString(),
                lengthBytes,
                warning
        );
    }

    /**
     * CR-049: cascade 병합된 최종 템플릿 문자열만 반환 (편의 메서드).
     */
    public String getMergedTemplateText(String key, String projectId) {
        return getMergedTemplate(key, projectId).merged();
    }

    private String firstActive(String key, String scope, String projectId) {
        String cacheKey = scopedCacheKey(key, scope, projectId);
        CacheEntry entry = cache.get(cacheKey);
        if (entry != null && !entry.isExpired()) {
            return entry.value;
        }
        try {
            List<PromptTemplateEntity> list = repository.findActiveByKeyAndScope(key, scope, projectId);
            if (!list.isEmpty()) {
                String tpl = list.get(0).getTemplate();
                cache.put(cacheKey, new CacheEntry(tpl));
                return tpl;
            }
        } catch (Exception e) {
            log.warn("scoped lookup failed for '{}' scope={} projectId={}: {}", key, scope, projectId, e.getMessage());
        }
        return null;
    }

    private String scopedCacheKey(String key, String scope, String projectId) {
        return key + "|" + scope + "|" + (projectId == null ? "-" : projectId);
    }

    /** cascade 병합 결과 DTO. */
    public record CascadeResult(
            String globalTemplate,
            String tenantTemplate,
            String projectTemplate,
            String merged,
            int totalLengthBytes,
            String warning
    ) {}

    /**
     * 프롬프트 조회 (DB 실패 시 파일 폴백, 파일도 없으면 fallback 반환).
     */
    public String getTemplateOrFallback(String key, String fallback) {
        String result = getTemplate(key);
        return result != null ? result : fallback;
    }

    /**
     * 카테고리별 벌크 로드 (Python 사이드카용).
     */
    public Map<String, String> bulkLoad(String category) {
        List<PromptTemplateEntity> entities;
        if (category != null && !category.isBlank()) {
            entities = repository.findByCategoryAndIsActiveTrue(category);
        } else {
            entities = repository.findByIsActiveTrue();
        }
        return entities.stream()
                .collect(Collectors.toMap(
                        e -> e.getPk().getKey(),
                        PromptTemplateEntity::getTemplate,
                        (a, b) -> a // 중복 시 첫 번째 유지
                ));
    }

    /**
     * 전체 벌크 로드.
     */
    public Map<String, String> bulkLoadAll() {
        return bulkLoad(null);
    }

    /**
     * 특정 키 캐시 무효화. CR-049 이후 scope 별 캐시 엔트리(key|scope|projectId) 도 함께 제거.
     */
    public void invalidateCache(String key) {
        cache.remove(key);
        String prefix = key + "|";
        cache.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /**
     * 전체 캐시 무효화.
     */
    public void invalidateAll() {
        cache.clear();
        log.info("Prompt template cache cleared");
    }

    /**
     * 템플릿 문자열에 변수 치환.
     */
    public String renderTemplate(String template, Map<String, Object> variables) {
        if (template == null || variables == null || variables.isEmpty()) return template;

        Matcher matcher = VAR_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String varName = matcher.group(1);
            Object value = variables.get(varName);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(
                    value != null ? value.toString() : "{{" + varName + "}}"));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    // --- private ---

    private String loadFromFile(String key) {
        String path = "prompts/" + key.replace('.', '/') + ".txt";
        try {
            ClassPathResource resource = new ClassPathResource(path);
            if (resource.exists()) {
                String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                cache.put(key, new CacheEntry(content));
                log.debug("Loaded prompt template '{}' from file fallback: {}", key, path);
                return content;
            }
        } catch (IOException e) {
            log.warn("Failed to load prompt file '{}': {}", path, e.getMessage());
        }
        return null;
    }

    private record CacheEntry(String value, long timestamp) {
        CacheEntry(String value) {
            this(value, Instant.now().toEpochMilli());
        }

        boolean isExpired() {
            return Instant.now().toEpochMilli() - timestamp > CACHE_TTL_MS;
        }
    }
}
