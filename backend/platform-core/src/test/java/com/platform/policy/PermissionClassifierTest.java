package com.platform.policy;

import com.platform.domain.PermissionRuleEntity;
import com.platform.repository.PermissionRuleRepository;
import com.platform.tool.PermissionLevel;
import com.platform.tool.ToolContractMeta;
import com.platform.tool.ToolRegistry;
import com.platform.tool.ToolScope;
import com.platform.tool.RetryPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * CR-030 PRD-196: PermissionClassifier 단위 테스트.
 */
@ExtendWith(MockitoExtension.class)
class PermissionClassifierTest {

    @Mock private PermissionRuleRepository ruleRepository;
    @Mock private ToolRegistry toolRegistry;

    private PermissionClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new PermissionClassifier(ruleRepository);
    }

    // ── DB 규칙 기반 분류 ──

    @Test
    void classify_readOnlyTools_shouldReturnReadOnly() {
        when(ruleRepository.findByIsActiveTrueOrderByPriorityDesc())
                .thenReturn(defaultRules());

        PermissionLevel result = classifier.classify(List.of("FileReadTool", "GlobTool"), toolRegistry);
        assertThat(result).isEqualTo(PermissionLevel.READ_ONLY);
    }

    @Test
    void classify_writeTools_shouldReturnRestrictedWrite() {
        when(ruleRepository.findByIsActiveTrueOrderByPriorityDesc())
                .thenReturn(defaultRules());

        PermissionLevel result = classifier.classify(List.of("FileReadTool", "SafeEditTool"), toolRegistry);
        assertThat(result).isEqualTo(PermissionLevel.RESTRICTED_WRITE);
    }

    @Test
    void classify_execTools_shouldReturnFull() {
        when(ruleRepository.findByIsActiveTrueOrderByPriorityDesc())
                .thenReturn(defaultRules());

        PermissionLevel result = classifier.classify(List.of("GlobTool", "BashTool"), toolRegistry);
        assertThat(result).isEqualTo(PermissionLevel.FULL);
    }

    @Test
    void classify_mixedTools_shouldReturnHighest() {
        when(ruleRepository.findByIsActiveTrueOrderByPriorityDesc())
                .thenReturn(defaultRules());

        PermissionLevel result = classifier.classify(
                List.of("FileReadTool", "SafeEditTool", "BashTool"), toolRegistry);
        assertThat(result).isEqualTo(PermissionLevel.FULL);
    }

    // ── ContractMeta 폴백 ──

    @Test
    void classify_noRuleMatch_shouldFallbackToContractMeta() {
        when(ruleRepository.findByIsActiveTrueOrderByPriorityDesc())
                .thenReturn(List.of());
        when(toolRegistry.getContractMeta("CustomWriteTool"))
                .thenReturn(new ToolContractMeta(
                        "CustomWriteTool", "1.0", ToolScope.EXTERNAL,
                        PermissionLevel.RESTRICTED_WRITE,
                        false, false, false, true,
                        RetryPolicy.NONE, List.of(), List.of("write")));

        PermissionLevel result = classifier.classify(List.of("CustomWriteTool"), toolRegistry);
        assertThat(result).isEqualTo(PermissionLevel.RESTRICTED_WRITE);
    }

    // ── fail-secure 폴백 ──

    @Test
    void classify_unknownTool_noRuleNoMeta_shouldReturnReadOnly() {
        when(ruleRepository.findByIsActiveTrueOrderByPriorityDesc())
                .thenReturn(List.of());
        when(toolRegistry.getContractMeta("UnknownTool")).thenReturn(null);

        PermissionLevel result = classifier.classify(List.of("UnknownTool"), toolRegistry);
        assertThat(result).isEqualTo(PermissionLevel.READ_ONLY);
    }

    @Test
    void classify_emptyToolList_shouldReturnReadOnly() {
        PermissionLevel result = classifier.classify(List.of(), toolRegistry);
        assertThat(result).isEqualTo(PermissionLevel.READ_ONLY);
    }

    @Test
    void classify_nullToolList_shouldReturnReadOnly() {
        PermissionLevel result = classifier.classify(null, toolRegistry);
        assertThat(result).isEqualTo(PermissionLevel.READ_ONLY);
    }

    // ── 규칙 우선순위 ──

    @Test
    void classify_higherPriorityRuleWins() {
        // 두 규칙 모두 "SafeEditTool"에 매칭되지만, priority 300이 먼저 적용
        PermissionRuleEntity fullRule = rule("exec-rule", ".*", "FULL", 300);
        PermissionRuleEntity readRule = rule("read-rule", ".*", "READ_ONLY", 100);
        when(ruleRepository.findByIsActiveTrueOrderByPriorityDesc())
                .thenReturn(List.of(fullRule, readRule)); // priority 내림차순

        PermissionLevel result = classifier.classify(List.of("SafeEditTool"), toolRegistry);
        assertThat(result).isEqualTo(PermissionLevel.FULL);
    }

    // ── 잘못된 정규식 처리 ──

    @Test
    void classify_invalidRegex_shouldSkipRule() {
        PermissionRuleEntity badRule = rule("bad", "[invalid(", "FULL", 500);
        PermissionRuleEntity goodRule = rule("good", "FileRead.*", "READ_ONLY", 100);
        when(ruleRepository.findByIsActiveTrueOrderByPriorityDesc())
                .thenReturn(List.of(badRule, goodRule));

        PermissionLevel result = classifier.classify(List.of("FileReadTool"), toolRegistry);
        assertThat(result).isEqualTo(PermissionLevel.READ_ONLY);
    }

    // ── 헬퍼 ──

    private List<PermissionRuleEntity> defaultRules() {
        return List.of(
                rule("perm-rule-exec",  "(?i)(Bash|Shell|Execute|ClaudeCode).*", "FULL", 300),
                rule("perm-rule-write", "(?i)(SafeEdit|PatchApply|Write|Create).*", "RESTRICTED_WRITE", 200),
                rule("perm-rule-read",  "(?i)(FileRead|Glob|Grep|Search|List).*", "READ_ONLY", 100)
        );
    }

    private PermissionRuleEntity rule(String id, String pattern, String level, int priority) {
        PermissionRuleEntity e = new PermissionRuleEntity();
        e.setId(id);
        e.setName(id);
        e.setToolNamePattern(pattern);
        e.setRequiredLevel(level);
        e.setPriority(priority);
        e.setActive(true);
        return e;
    }
}
