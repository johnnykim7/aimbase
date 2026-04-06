package com.platform.hook;

import com.platform.domain.HookDefinitionEntity;
import com.platform.repository.HookDefinitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CR-030 PRD-190: 훅 레지스트리.
 *
 * DB에서 훅 정의를 로딩하고 이벤트별로 정렬된 목록을 제공한다.
 * 캐시하여 매 호출마다 DB 조회를 방지하며,
 * refresh()로 갱신할 수 있다.
 */
@Component
public class HookRegistry {

    private static final Logger log = LoggerFactory.getLogger(HookRegistry.class);

    private final HookDefinitionRepository repository;

    /** 이벤트별 훅 정의 캐시 (exec_order 정렬) */
    private final Map<HookEvent, List<HookDefinitionEntity>> cache = new ConcurrentHashMap<>();
    private volatile boolean loaded = false;

    public HookRegistry(HookDefinitionRepository repository) {
        this.repository = repository;
    }

    /**
     * 이벤트에 매칭되는 활성 훅 정의 목록 반환 (exec_order 정렬).
     */
    public List<HookDefinitionEntity> getHooksFor(HookEvent event) {
        ensureLoaded();
        return cache.getOrDefault(event, List.of());
    }

    /**
     * 이벤트 + 도구명에 매칭되는 훅 정의 목록 반환.
     * matcher가 null이면 모든 도구에 매칭, 아니면 glob 패턴 매칭.
     */
    public List<HookDefinitionEntity> getHooksFor(HookEvent event, String toolName) {
        return getHooksFor(event).stream()
                .filter(h -> matchesTool(h.getMatcher(), toolName))
                .toList();
    }

    /**
     * 캐시를 DB에서 다시 로딩.
     */
    public void refresh() {
        try {
            List<HookDefinitionEntity> all = repository.findByIsActiveTrueOrderByEventAscExecOrderAsc();
            Map<HookEvent, List<HookDefinitionEntity>> newCache = new ConcurrentHashMap<>();
            for (HookDefinitionEntity def : all) {
                try {
                    HookEvent event = HookEvent.valueOf(def.getEvent());
                    newCache.computeIfAbsent(event, k -> new java.util.ArrayList<>()).add(def);
                } catch (IllegalArgumentException e) {
                    log.warn("Unknown hook event type: {} (hook={})", def.getEvent(), def.getId());
                }
            }
            cache.clear();
            cache.putAll(newCache);
            loaded = true;
            log.info("Hook registry refreshed: {} definitions loaded", all.size());
        } catch (Exception e) {
            log.warn("Failed to refresh hook registry: {}", e.getMessage());
            loaded = true; // 실패해도 빈 캐시로 동작 (fail-open)
        }
    }

    /**
     * 등록된 훅이 있는지 빠르게 확인 (이벤트별).
     */
    public boolean hasHooks(HookEvent event) {
        ensureLoaded();
        List<HookDefinitionEntity> hooks = cache.get(event);
        return hooks != null && !hooks.isEmpty();
    }

    private void ensureLoaded() {
        if (!loaded) {
            synchronized (this) {
                if (!loaded) {
                    refresh();
                }
            }
        }
    }

    /**
     * glob 패턴 매칭 (간이 구현: * = 모든 문자열, ? = 단일 문자).
     * matcher가 null이면 무조건 매칭.
     */
    private boolean matchesTool(String matcher, String toolName) {
        if (matcher == null || matcher.isBlank() || "*".equals(matcher)) {
            return true;
        }
        if (toolName == null) {
            return false;
        }
        // 간단한 glob → regex 변환
        String regex = matcher.replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".");
        return toolName.matches(regex);
    }
}
