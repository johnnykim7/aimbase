package com.platform.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CR-029: 런타임 레지스트리.
 * 등록된 RuntimeAdapter를 관리하고, 기준에 따라 최적 런타임을 선택한다.
 */
@Component
public class RuntimeRegistry {

    private static final Logger log = LoggerFactory.getLogger(RuntimeRegistry.class);

    private final Map<String, RuntimeAdapter> adapters = new ConcurrentHashMap<>();

    public RuntimeRegistry(List<RuntimeAdapter> runtimeAdapters) {
        runtimeAdapters.forEach(adapter -> {
            adapters.put(adapter.getRuntimeId(), adapter);
            log.info("Registered runtime adapter: {}", adapter.getRuntimeId());
        });
    }

    /** 런타임 ID로 직접 해석 */
    public RuntimeAdapter resolve(String runtimeId) {
        return adapters.get(runtimeId);
    }

    /**
     * 기준에 따라 최적 런타임을 선택.
     * preferredRuntime이 있으면 우선 사용, 없으면 능력 매칭.
     */
    public RuntimeAdapter selectBest(RuntimeSelectionCriteria criteria) {
        // 1. 명시 지정
        if (criteria.preferredRuntime() != null) {
            RuntimeAdapter preferred = adapters.get(criteria.preferredRuntime());
            if (preferred != null) return preferred;
            log.warn("Preferred runtime '{}' not found, falling back to auto selection",
                    criteria.preferredRuntime());
        }

        // 2. 능력 매칭
        RuntimeAdapter best = null;
        int bestScore = -1;

        for (RuntimeAdapter adapter : adapters.values()) {
            int score = scoreAdapter(adapter, criteria);
            if (score > bestScore) {
                bestScore = score;
                best = adapter;
            }
        }

        if (best == null) {
            // fallback: llm_api가 기본
            best = adapters.get("llm_api");
        }

        return best;
    }

    /** 런타임 능력 목록 조회 */
    public List<RuntimeCapabilityProfile> listCapabilities() {
        return adapters.values().stream()
                .map(RuntimeAdapter::getCapabilities)
                .toList();
    }

    private int scoreAdapter(RuntimeAdapter adapter, RuntimeSelectionCriteria criteria) {
        RuntimeCapabilityProfile cap = adapter.getCapabilities();
        int score = 0;

        if (criteria.requiresToolUse() && cap.supportsToolUse()) score += 10;
        if (criteria.requiresLongContext() && cap.supportsLongContext()) score += 10;
        if (criteria.requiresAutonomous() && cap.supportsAutonomousExploration()) score += 20;

        // taskType 매칭
        if (criteria.taskType() != null && cap.strengths().contains(criteria.taskType())) {
            score += 15;
        }

        return score;
    }
}
