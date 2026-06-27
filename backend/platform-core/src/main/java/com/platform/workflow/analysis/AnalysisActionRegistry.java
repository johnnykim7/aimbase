package com.platform.workflow.analysis;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * CR-120: 분석 동작 레지스트리 — actionId → DocumentAnalysis 디스패치.
 *
 * <p>Spring 이 {@code List<DocumentAnalysis>} 로 모든 @Component 구현을 자동 수집한다(AdapterRegistry 패턴).
 * 새 동작을 추가해도 이 클래스는 무수정 — Spring DI 가 자동 등록한다(무한 확장의 구현체).
 */
@Component
public class AnalysisActionRegistry {

    private static final Logger log = LoggerFactory.getLogger(AnalysisActionRegistry.class);

    private final Map<String, DocumentAnalysis> byId;

    public AnalysisActionRegistry(List<DocumentAnalysis> actions) {
        this.byId = actions.stream()
                .collect(Collectors.toMap(DocumentAnalysis::actionId, Function.identity()));
    }

    @PostConstruct
    void logRegistered() {
        log.info("CR-120 AnalysisActionRegistry: {} actions registered: {}", byId.size(), byId.keySet());
    }

    /** actionId 로 동작 조회. 미등록이면 IllegalArgumentException (config 오타·미구현 동작 즉시 노출). */
    public DocumentAnalysis get(String actionId) {
        DocumentAnalysis a = byId.get(actionId);
        if (a == null) {
            throw new IllegalArgumentException(
                    "CR-120: unknown analysis_action '" + actionId + "'. registered: " + byId.keySet());
        }
        return a;
    }

    /** 등록된 동작 id 목록 (UI 동적 드롭다운용). */
    public Set<String> ids() {
        return byId.keySet();
    }
}
