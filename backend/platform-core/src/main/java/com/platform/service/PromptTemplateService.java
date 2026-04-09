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
import java.util.*;
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
     * 특정 키 캐시 무효화.
     */
    public void invalidateCache(String key) {
        cache.remove(key);
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
