package com.platform.tool.registry;

import com.platform.tool.model.UnifiedToolDef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-067 후속: SessionToolRegistry DEFAULT_ACTIVE + NAME_ALIASES 회귀 테스트.
 *
 * <p>작년 4월 벤치마크 회귀 분석에서 발견한 결함 — Glob/WorkspaceSnapshot/PathInfo 가
 * DEFAULT_ACTIVE 에 빠지고 NAME_ALIASES 가 builtin_* prefix 를 매핑 안 해서
 * filterActive 가 첫 턴 도구 schema 를 5개로 좁혀버린 사례를 잠근다.
 */
class SessionToolRegistryTest {

    private final SessionToolRegistry registry = new SessionToolRegistry();

    private static UnifiedToolDef def(String name) {
        return new UnifiedToolDef(name, "desc-" + name, Map.of("type", "object"));
    }

    @Test
    @DisplayName("DEFAULT_ACTIVE 에 탐색 핵심 도구(Read/Edit/Grep/Glob/Bash/PathInfo/WorkspaceSnapshot/TodoWrite/ToolSearch/ReadToolResult) 모두 포함")
    void default_active_contains_essential_navigation_tools() {
        assertThat(SessionToolRegistry.DEFAULT_ACTIVE)
                .contains("Read", "Edit", "Grep", "Glob", "Bash",
                        "PathInfo", "WorkspaceSnapshot",
                        "TodoWrite", "ToolSearch", "ReadToolResult");
    }

    @Test
    @DisplayName("filterActive: 실제 등록 도구 이름(builtin_*) 이 NAME_ALIASES 로 DEFAULT_ACTIVE 매칭")
    void filter_active_matches_builtin_prefixed_tools() {
        // 실제 ToolRegistry 가 등록하는 이름들 (builtin_ prefix 등)
        List<UnifiedToolDef> all = List.of(
                def("builtin_file_read"),       // → Read
                def("builtin_safe_edit"),       // → Edit
                def("builtin_grep"),            // → Grep
                def("builtin_glob"),            // → Glob
                def("bash"),                    // → Bash
                def("builtin_path_info"),       // → PathInfo
                def("builtin_workspace_snapshot"),// → WorkspaceSnapshot
                def("todo_write"),              // → TodoWrite
                def("tool_search"),             // → ToolSearch
                def("read_tool_result"),        // → ReadToolResult
                def("notebook_edit"),           // 비활성: DEFAULT_ACTIVE 외
                def("web_search"),              // 비활성
                def("http_request")             // 비활성
        );

        List<UnifiedToolDef> filtered = registry.filterActive("session-A", all);

        // DEFAULT_ACTIVE 에 매칭되는 10개만 통과해야 함 (회귀 잠금)
        assertThat(filtered).extracting(UnifiedToolDef::name)
                .containsExactlyInAnyOrder(
                        "builtin_file_read",
                        "builtin_safe_edit",
                        "builtin_grep",
                        "builtin_glob",
                        "bash",
                        "builtin_path_info",
                        "builtin_workspace_snapshot",
                        "todo_write",
                        "tool_search",
                        "read_tool_result"
                );
    }

    @Test
    @DisplayName("filterActive: sessionId null 이면 원본 그대로 반환 (워크플로우/관리 API 보호)")
    void filter_active_passthrough_when_session_null() {
        List<UnifiedToolDef> all = List.of(def("any_tool"), def("another"));
        assertThat(registry.filterActive(null, all)).isEqualTo(all);
        assertThat(registry.filterActive("", all)).isEqualTo(all);
        assertThat(registry.filterActive("  ", all)).isEqualTo(all);
    }

    @Test
    @DisplayName("activate 로 추가 도구를 활성화하면 다음 filterActive 결과에 포함")
    void activate_adds_tool_to_session_set() {
        registry.activate("session-B", "web_search");
        List<UnifiedToolDef> all = List.of(def("builtin_file_read"), def("web_search"), def("notebook_edit"));
        assertThat(registry.filterActive("session-B", all)).extracting(UnifiedToolDef::name)
                .contains("builtin_file_read", "web_search")
                .doesNotContain("notebook_edit");
    }
}
