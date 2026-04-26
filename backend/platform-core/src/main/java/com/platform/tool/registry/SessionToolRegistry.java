package com.platform.tool.registry;

import com.platform.tool.model.UnifiedToolDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CR-048 PRD-300: 세션별 활성 도구 레지스트리.
 *
 * 세션 초기에는 기본 활성 세트(Read/Edit/Grep/Bash/TodoWrite/ToolSearch/ReadToolResult)만
 * tool defs에 포함시키고, 그 외 도구는 이름+description만 system prompt 후미 텍스트로 노출.
 * 모델이 ToolSearch를 호출하면 결과에 포함된 도구를 여기에 activate 하여 다음 턴부터 스키마가 주입된다.
 *
 * Anthropic cache_control prefix는 고정 유지 — 활성 도구 변경은 tool defs 후반부 + system prompt 뒤쪽에만 영향.
 */
@Component
public class SessionToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionToolRegistry.class);

    /**
     * 기본 활성 도구 세트.
     *
     * <p>CR-067 후속 (2026-04-26): 작년 4월 벤치마크 회귀 분석 결과 — Glob/WorkspaceSnapshot/PathInfo 가
     * 빠지면 모델이 워크스페이스 탐색 자체를 못 해 ToolSearch 호출 전에 환각 답변을 만드는 사례 다수.
     * 첫 턴부터 모델이 탐색→Read 흐름을 자율 결정할 수 있도록 핵심 탐색 도구를 기본 활성에 포함.
     */
    public static final Set<String> DEFAULT_ACTIVE = Set.of(
            "Read",
            "Edit",
            "Grep",
            "Glob",
            "Bash",
            "PathInfo",
            "WorkspaceSnapshot",
            "TodoWrite",
            "ToolSearch",
            "ReadToolResult"
    );

    /**
     * 별칭 매핑 — 도구 이름이 snake_case/PascalCase/builtin_ prefix 혼재할 경우 기본 세트 매칭.
     *
     * <p>CR-067 후속: 실제 등록 도구 이름(`builtin_*` prefix 포함)이 키에 들어있어야 매칭됨.
     * 누락된 alias 가 있으면 DEFAULT_ACTIVE 에 들어있어도 filterActive 가 빠뜨림.
     * 키는 모두 lowercase (isActive 가 toLowerCase 비교).
     */
    private static final java.util.Map<String, String> NAME_ALIASES = java.util.Map.ofEntries(
            // Read (file_read)
            java.util.Map.entry("read", "Read"),
            java.util.Map.entry("file_read", "Read"),
            java.util.Map.entry("builtin_file_read", "Read"),
            // Edit (safe_edit)
            java.util.Map.entry("edit", "Edit"),
            java.util.Map.entry("safe_edit", "Edit"),
            java.util.Map.entry("builtin_safe_edit", "Edit"),
            // Grep
            java.util.Map.entry("grep", "Grep"),
            java.util.Map.entry("builtin_grep", "Grep"),
            // Glob
            java.util.Map.entry("glob", "Glob"),
            java.util.Map.entry("builtin_glob", "Glob"),
            // Bash
            java.util.Map.entry("bash", "Bash"),
            // PathInfo
            java.util.Map.entry("path_info", "PathInfo"),
            java.util.Map.entry("builtin_path_info", "PathInfo"),
            // WorkspaceSnapshot
            java.util.Map.entry("workspace_snapshot", "WorkspaceSnapshot"),
            java.util.Map.entry("builtin_workspace_snapshot", "WorkspaceSnapshot"),
            // TodoWrite
            java.util.Map.entry("todo_write", "TodoWrite"),
            // ToolSearch
            java.util.Map.entry("tool_search", "ToolSearch"),
            // ReadToolResult
            java.util.Map.entry("read_tool_result", "ReadToolResult")
    );

    /** sessionId → 활성화된 도구 이름 집합 */
    private final ConcurrentHashMap<String, Set<String>> active = new ConcurrentHashMap<>();

    /** 세션의 활성 도구 집합 조회 (없으면 기본 세트로 초기화). */
    public Set<String> getActive(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return DEFAULT_ACTIVE;
        }
        return active.computeIfAbsent(sessionId,
                k -> Collections.synchronizedSet(new LinkedHashSet<>(DEFAULT_ACTIVE)));
    }

    /** 세션에 도구를 활성화. 이미 활성화된 도구는 무시. */
    public void activate(String sessionId, String toolName) {
        if (sessionId == null || toolName == null) return;
        Set<String> set = getActive(sessionId);
        if (set.add(toolName)) {
            log.debug("Activated tool '{}' in session {}", toolName, sessionId);
        }
    }

    /** 여러 도구 일괄 활성화. 신규 활성화된 이름들만 반환. */
    public List<String> activateAll(String sessionId, List<String> toolNames) {
        if (sessionId == null || toolNames == null || toolNames.isEmpty()) return List.of();
        Set<String> set = getActive(sessionId);
        java.util.List<String> added = new java.util.ArrayList<>();
        for (String name : toolNames) {
            if (name != null && set.add(name)) {
                added.add(name);
            }
        }
        if (!added.isEmpty()) {
            log.info("Session {} activated {} new tool(s): {}", sessionId, added.size(), added);
        }
        return added;
    }

    /** 세션 종료 시 호출 (메모리 정리). */
    public void remove(String sessionId) {
        if (sessionId == null) return;
        active.remove(sessionId);
    }

    /**
     * 주어진 전체 tool defs를 세션의 활성 집합으로 필터링.
     * sessionId가 없으면 원본을 그대로 반환(워크플로우/관리 API 등 세션 밖 경로 보호).
     */
    public List<UnifiedToolDef> filterActive(String sessionId, List<UnifiedToolDef> allDefs) {
        if (sessionId == null || sessionId.isBlank() || allDefs == null || allDefs.isEmpty()) {
            return allDefs;
        }
        Set<String> activeSet = getActive(sessionId);
        return allDefs.stream()
                .filter(def -> isActive(activeSet, def.name()))
                .toList();
    }

    /** 기본 활성 세트에 포함되는지 판정 (alias 반영). */
    private boolean isActive(Set<String> activeSet, String toolName) {
        if (toolName == null) return false;
        if (activeSet.contains(toolName)) return true;
        String alias = NAME_ALIASES.get(toolName.toLowerCase());
        return alias != null && activeSet.contains(alias);
    }
}
