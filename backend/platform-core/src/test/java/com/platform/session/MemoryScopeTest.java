package com.platform.session;

import com.platform.domain.ConversationMemoryEntity;
import com.platform.llm.model.UnifiedMessage;
import com.platform.repository.ConversationMemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * CR-030 Phase 4: MemoryScope 우선순위 병합 테스트 (PRD-199~202).
 */
@ExtendWith(MockitoExtension.class)
class MemoryScopeTest {

    @Mock private ConversationMemoryRepository memoryRepository;

    private MemoryService memoryService;

    private static final String SESSION = "sess-1";
    private static final String USER = "user-1";
    private static final String TEAM = "team-alpha";

    @BeforeEach
    void setUp() {
        memoryService = new MemoryService(memoryRepository);
    }

    // ── PRD-199: MemoryScope enum ──

    @Test
    void memoryScope_hasThreeValues() {
        assertThat(MemoryScope.values()).containsExactly(
                MemoryScope.PRIVATE, MemoryScope.TEAM, MemoryScope.GLOBAL);
    }

    // ── PRD-202: PRIVATE만 있을 때 기존 동작 유지 ──

    @Test
    void buildMemoryContext_privateOnly_backwardsCompatible() {
        var privateRule = makeMemory("SYSTEM_RULES", "PRIVATE", "private rule", null);
        when(memoryRepository.findBySessionIdAndMemoryTypeAndScopeOrderByCreatedAtDesc(
                SESSION, "SYSTEM_RULES", "PRIVATE"))
                .thenReturn(List.of(privateRule));
        when(memoryRepository.findByScopeAndMemoryTypeOrderByCreatedAtDesc("GLOBAL", "SYSTEM_RULES"))
                .thenReturn(List.of());
        when(memoryRepository.findBySessionIdAndMemoryTypeAndScopeOrderByCreatedAtDesc(
                SESSION, "LONG_TERM", "PRIVATE"))
                .thenReturn(List.of());
        when(memoryRepository.findByScopeAndMemoryTypeOrderByCreatedAtDesc("GLOBAL", "LONG_TERM"))
                .thenReturn(List.of());
        when(memoryRepository.findByUserIdAndMemoryTypeOrderByCreatedAtDesc(USER, "USER_PROFILE"))
                .thenReturn(List.of());

        List<UnifiedMessage> result = memoryService.buildMemoryContext(SESSION, USER, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).content().toString()).contains("[규칙] private rule");
    }

    // ── PRD-202: PRIVATE > TEAM > GLOBAL 우선순위 ──

    @Test
    void buildMemoryContext_priorityMerge_privateOverTeamOverGlobal() {
        var privateRule = makeMemory("SYSTEM_RULES", "PRIVATE", "shared rule", null);
        var teamRule = makeMemory("SYSTEM_RULES", "TEAM", "shared rule", TEAM); // 중복
        var teamOnly = makeMemory("SYSTEM_RULES", "TEAM", "team only rule", TEAM);
        var globalRule = makeMemory("SYSTEM_RULES", "GLOBAL", "global rule", null);
        var globalDup = makeMemory("SYSTEM_RULES", "GLOBAL", "shared rule", null); // 중복

        when(memoryRepository.findBySessionIdAndMemoryTypeAndScopeOrderByCreatedAtDesc(
                SESSION, "SYSTEM_RULES", "PRIVATE"))
                .thenReturn(List.of(privateRule));
        when(memoryRepository.findByTeamIdAndMemoryTypeAndScopeOrderByCreatedAtDesc(
                TEAM, "SYSTEM_RULES", "TEAM"))
                .thenReturn(List.of(teamRule, teamOnly));
        when(memoryRepository.findByScopeAndMemoryTypeOrderByCreatedAtDesc("GLOBAL", "SYSTEM_RULES"))
                .thenReturn(List.of(globalRule, globalDup));

        // LONG_TERM, USER_PROFILE — 빈 결과
        when(memoryRepository.findBySessionIdAndMemoryTypeAndScopeOrderByCreatedAtDesc(
                SESSION, "LONG_TERM", "PRIVATE"))
                .thenReturn(List.of());
        when(memoryRepository.findByTeamIdAndMemoryTypeAndScopeOrderByCreatedAtDesc(
                TEAM, "LONG_TERM", "TEAM"))
                .thenReturn(List.of());
        when(memoryRepository.findByScopeAndMemoryTypeOrderByCreatedAtDesc("GLOBAL", "LONG_TERM"))
                .thenReturn(List.of());
        when(memoryRepository.findByUserIdAndMemoryTypeOrderByCreatedAtDesc(USER, "USER_PROFILE"))
                .thenReturn(List.of());

        List<UnifiedMessage> result = memoryService.buildMemoryContext(SESSION, USER, TEAM);

        // "shared rule"은 PRIVATE에서 이미 주입 → TEAM, GLOBAL 중복 제거
        assertThat(result).hasSize(3);
        assertThat(result.get(0).content().toString()).contains("[규칙] shared rule");
        assertThat(result.get(1).content().toString()).contains("[팀 규칙] team only rule");
        assertThat(result.get(2).content().toString()).contains("[글로벌 규칙] global rule");
    }

    // ── PRD-202: LONG_TERM scope 병합 ──

    @Test
    void buildMemoryContext_longTerm_mergedWithTopK() {
        var privateLT = makeMemory("LONG_TERM", "PRIVATE", "private memory", null);
        var teamLT = makeMemory("LONG_TERM", "TEAM", "team memory", TEAM);
        var globalLT = makeMemory("LONG_TERM", "GLOBAL", "global memory", null);

        when(memoryRepository.findBySessionIdAndMemoryTypeAndScopeOrderByCreatedAtDesc(
                SESSION, "SYSTEM_RULES", "PRIVATE")).thenReturn(List.of());
        when(memoryRepository.findByTeamIdAndMemoryTypeAndScopeOrderByCreatedAtDesc(
                TEAM, "SYSTEM_RULES", "TEAM")).thenReturn(List.of());
        when(memoryRepository.findByScopeAndMemoryTypeOrderByCreatedAtDesc("GLOBAL", "SYSTEM_RULES"))
                .thenReturn(List.of());

        when(memoryRepository.findBySessionIdAndMemoryTypeAndScopeOrderByCreatedAtDesc(
                SESSION, "LONG_TERM", "PRIVATE")).thenReturn(List.of(privateLT));
        when(memoryRepository.findByTeamIdAndMemoryTypeAndScopeOrderByCreatedAtDesc(
                TEAM, "LONG_TERM", "TEAM")).thenReturn(List.of(teamLT));
        when(memoryRepository.findByScopeAndMemoryTypeOrderByCreatedAtDesc("GLOBAL", "LONG_TERM"))
                .thenReturn(List.of(globalLT));
        when(memoryRepository.findByUserIdAndMemoryTypeOrderByCreatedAtDesc(USER, "USER_PROFILE"))
                .thenReturn(List.of());

        List<UnifiedMessage> result = memoryService.buildMemoryContext(SESSION, USER, TEAM);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).content().toString()).contains("[장기 기억] private memory");
        assertThat(result.get(1).content().toString()).contains("[팀 기억] team memory");
        assertThat(result.get(2).content().toString()).contains("[글로벌 기억] global memory");
    }

    // ── PRD-202: teamId null일 때 TEAM 쿼리 스킵 ──

    @Test
    void buildMemoryContext_noTeamId_skipsTeamScope() {
        var privateRule = makeMemory("SYSTEM_RULES", "PRIVATE", "only private", null);
        var globalRule = makeMemory("SYSTEM_RULES", "GLOBAL", "global shared", null);

        when(memoryRepository.findBySessionIdAndMemoryTypeAndScopeOrderByCreatedAtDesc(
                SESSION, "SYSTEM_RULES", "PRIVATE")).thenReturn(List.of(privateRule));
        when(memoryRepository.findByScopeAndMemoryTypeOrderByCreatedAtDesc("GLOBAL", "SYSTEM_RULES"))
                .thenReturn(List.of(globalRule));
        when(memoryRepository.findBySessionIdAndMemoryTypeAndScopeOrderByCreatedAtDesc(
                SESSION, "LONG_TERM", "PRIVATE")).thenReturn(List.of());
        when(memoryRepository.findByScopeAndMemoryTypeOrderByCreatedAtDesc("GLOBAL", "LONG_TERM"))
                .thenReturn(List.of());
        when(memoryRepository.findByUserIdAndMemoryTypeOrderByCreatedAtDesc(USER, "USER_PROFILE"))
                .thenReturn(List.of());

        List<UnifiedMessage> result = memoryService.buildMemoryContext(SESSION, USER, null);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).content().toString()).contains("[규칙] only private");
        assertThat(result.get(1).content().toString()).contains("[글로벌 규칙] global shared");
    }

    // ── PRD-201: TeamMemoryService 저장/조회 ──

    @Test
    void teamMemoryService_save_setsScopeAndTeamId() {
        TeamMemoryService teamService = new TeamMemoryService(memoryRepository);

        var saved = makeMemory("LONG_TERM", "TEAM", "team insight", TEAM);
        when(memoryRepository.save(org.mockito.ArgumentMatchers.any())).thenReturn(saved);

        ConversationMemoryEntity result = teamService.save(TEAM, USER, MemoryLayer.LONG_TERM, "team insight");

        assertThat(result.getScope()).isEqualTo("TEAM");
        assertThat(result.getTeamId()).isEqualTo(TEAM);
    }

    // ── Helper ──

    private ConversationMemoryEntity makeMemory(String type, String scope, String content, String teamId) {
        ConversationMemoryEntity e = new ConversationMemoryEntity();
        e.setMemoryType(type);
        e.setScope(scope);
        e.setContent(content);
        e.setTeamId(teamId);
        e.setSessionId(SESSION);
        e.setUserId(USER);
        return e;
    }
}
