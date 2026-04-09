package com.platform.policy;

import com.platform.domain.PermissionRuleEntity;
import com.platform.repository.PermissionRuleRepository;
import com.platform.tool.PermissionLevel;
import com.platform.tool.ToolContractMeta;
import com.platform.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * CR-030 PRD-196: 자동 권한 분류기.
 *
 * PermissionLevel.AUTO가 설정된 경우, 호출 대상 도구 목록을 분석하여
 * 적절한 구체 PermissionLevel을 결정한다.
 *
 * 분류 우선순위:
 * 1. DB PermissionRule 패턴 매칭 (priority 내림차순)
 * 2. 도구 ToolContractMeta의 permissionLevel
 * 3. 폴백: READ_ONLY (fail-secure)
 */
@Component
public class PermissionClassifier {

    private static final Logger log = LoggerFactory.getLogger(PermissionClassifier.class);

    private final PermissionRuleRepository ruleRepository;

    public PermissionClassifier(PermissionRuleRepository ruleRepository) {
        this.ruleRepository = ruleRepository;
    }

    /**
     * 도구 이름 목록에서 필요한 최대 권한 수준을 결정.
     * 모든 도구를 실행하려면 그 중 가장 높은 권한이 필요하므로 max를 반환.
     *
     * @param toolNames 이번 턴에서 호출될 도구 이름 목록
     * @param toolRegistry 도구 레지스트리 (ContractMeta 조회용)
     * @return 해소된 구체 PermissionLevel (AUTO가 아님)
     */
    public PermissionLevel classify(List<String> toolNames, ToolRegistry toolRegistry) {
        if (toolNames == null || toolNames.isEmpty()) {
            log.debug("No tools to classify — fallback to READ_ONLY");
            return PermissionLevel.READ_ONLY;
        }

        List<PermissionRuleEntity> rules = loadRules();
        PermissionLevel resolved = PermissionLevel.READ_ONLY;

        for (String toolName : toolNames) {
            PermissionLevel toolLevel = classifyTool(toolName, rules, toolRegistry);
            if (toolLevel.ordinal() > resolved.ordinal()) {
                resolved = toolLevel;
            }
            // 이미 최대 수준이면 더 볼 필요 없음
            if (resolved == PermissionLevel.FULL) break;
        }

        log.debug("Permission classified: tools={} → {}", toolNames, resolved);
        return resolved;
    }

    /**
     * 단일 도구에 대한 권한 분류.
     *
     * 1단계: DB 규칙에서 패턴 매칭 (priority 내림차순, 첫 매칭 사용)
     * 2단계: 도구의 ToolContractMeta.permissionLevel
     * 3단계: READ_ONLY 폴백
     */
    PermissionLevel classifyTool(String toolName, List<PermissionRuleEntity> rules,
                                          ToolRegistry toolRegistry) {
        // 1. DB 규칙 패턴 매칭
        for (PermissionRuleEntity rule : rules) {
            try {
                if (Pattern.matches(rule.getToolNamePattern(), toolName)) {
                    PermissionLevel level = parseLevel(rule.getRequiredLevel());
                    log.trace("Tool '{}' matched rule '{}' → {}", toolName, rule.getName(), level);
                    return level;
                }
            } catch (PatternSyntaxException e) {
                log.warn("Invalid regex in permission rule '{}': {}", rule.getId(), e.getMessage());
            }
        }

        // 2. ToolContractMeta에서 도구 자체 선언 권한 사용
        ToolContractMeta meta = toolRegistry.getContractMeta(toolName);
        if (meta != null && meta.permissionLevel() != null) {
            log.trace("Tool '{}' using contract meta level: {}", toolName, meta.permissionLevel());
            return meta.permissionLevel();
        }

        // 3. fail-secure 폴백
        log.trace("Tool '{}' no rule/meta match — fallback to READ_ONLY", toolName);
        return PermissionLevel.READ_ONLY;
    }

    private List<PermissionRuleEntity> loadRules() {
        try {
            return ruleRepository.findByIsActiveTrueOrderByPriorityDesc();
        } catch (Exception e) {
            log.warn("Failed to load permission rules, using empty: {}", e.getMessage());
            return List.of();
        }
    }

    private PermissionLevel parseLevel(String level) {
        try {
            return PermissionLevel.valueOf(level);
        } catch (IllegalArgumentException e) {
            log.warn("Unknown permission level '{}', fallback to READ_ONLY", level);
            return PermissionLevel.READ_ONLY;
        }
    }
}
