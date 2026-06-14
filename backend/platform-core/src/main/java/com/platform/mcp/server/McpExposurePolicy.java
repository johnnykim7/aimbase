package com.platform.mcp.server;

import com.platform.tool.EnhancedToolExecutor;
import com.platform.tool.McpExposureLevel;
import com.platform.tool.ToolContractMeta;
import com.platform.tool.ToolExecutor;

import java.util.Map;
import java.util.Set;

/**
 * CR-072: 서버 도구의 MCP 노출 정책.
 *
 * <p>도구별 {@link McpExposureLevel} 결정 우선순위:
 * <ol>
 *   <li>도구가 {@link ToolContractMeta#mcpExposureLevel()} 로 명시 선언한 값 (기본 NONE 외)</li>
 *   <li>이 클래스의 화이트리스트 매핑 (도구 이름 기준)</li>
 *   <li>기본값 {@link McpExposureLevel#NONE}</li>
 * </ol>
 *
 * <p>매핑은 보수적으로 관리 — 새 도구는 기본 NONE, 명시 등록된 것만 노출.
 * 명시 NONE 등록은 의도 명확화를 위한 문서 효과.</p>
 */
public final class McpExposurePolicy {

    /**
     * CR 노출 레벨: CLI 두뇌(Claude CLI 등)가 호출 가능한 도구 (43개).
     *
     * <p>CR-104 불변식: 이 목록은 API 어댑터가 {@code LLMRequest.tools} 로 모델에 전달하는 도구
     * 목록({@code ToolRegistry.getToolDefs}, 전체 builtin 48개 중 toolFilter 허용분)과 동일 집합이어야 한다.
     * 워크플로우를 CLI 커넥터로 돌릴 때, 같은 스텝이 API 어댑터면 동작하고 CLI 어댑터면
     * "No such tool" 로 실패하던 회귀의 근본 원인 = 두 목록의 출처 불일치였다.
     * 따라서 {@link #EXPLICITLY_NONE}(내부 관리/계획 전용) 6개를 제외한 전체 builtin 을 노출한다.</p>
     */
    private static final Set<String> CLI_EXPOSED = Set.of(
            // Network
            "web_search", "http_request",
            // Collaboration
            "send_message", "send_notification",
            // AI / Content
            "brief", "analyze_image", "translate_text", "suggest_background_pr",
            // OCR (CR-092)
            "ocr_image",
            // Document parsing (CR-094)
            "parse_document",
            // File / Result
            "notebook_edit", "read_tool_result",
            // CLI / LSP
            "lsp", "skill_invoke",
            // Discovery
            "tool_search",
            // MCP 메타
            "list_mcp_resources", "read_mcp_resource", "remote_trigger",
            // Cron
            "schedule_cron", "cron_list", "cron_delete",
            // Task
            "task_create", "task_get", "task_list", "task_update", "task_output", "task_stop",
            // Other
            "todo_write",
            // CR-104: 워크플로우 필수 native/유틸 도구 — API 경로엔 항상 노출되던 것을 CLI 경로에도 동일 노출.
            // Shell / 파일 쓰기 / 다운로드
            "bash", "file_write", "download_file",
            // Native 파일 탐색·읽기·편집 (tool-sdk-core nativetool)
            "builtin_grep", "builtin_file_read", "builtin_glob", "builtin_safe_edit",
            "builtin_patch_apply", "builtin_structured_search", "builtin_document_section_read",
            "builtin_path_info", "builtin_workspace_snapshot",
            // 유틸
            "zip_extract", "calculate", "get_current_time"
    );

    /** NONE 노출: 서버 내부 전용 (관리/계획/유지보수 도구 6개). */
    private static final Set<String> EXPLICITLY_NONE = Set.of(
            "team_create", "team_delete",
            "enter_plan_mode", "exit_plan_mode", "verify_plan_execution",
            "temp_cleanup"
    );

    private static final Map<String, McpExposureLevel> NAME_TO_LEVEL = buildMap();

    private static Map<String, McpExposureLevel> buildMap() {
        var map = new java.util.HashMap<String, McpExposureLevel>();
        CLI_EXPOSED.forEach(name -> map.put(name, McpExposureLevel.CLI));
        EXPLICITLY_NONE.forEach(name -> map.put(name, McpExposureLevel.NONE));
        return Map.copyOf(map);
    }

    private McpExposurePolicy() {
    }

    /** 도구의 노출 레벨 결정. 메타가 명시했으면 그게 우선, 없으면 매핑, 그것도 없으면 NONE. */
    public static McpExposureLevel resolve(ToolExecutor executor) {
        if (executor instanceof EnhancedToolExecutor enhanced) {
            ToolContractMeta meta = enhanced.getContractMeta();
            if (meta != null && meta.mcpExposureLevel() != McpExposureLevel.NONE) {
                return meta.mcpExposureLevel();
            }
        }
        String name = executor.getDefinition().name();
        return NAME_TO_LEVEL.getOrDefault(name, McpExposureLevel.NONE);
    }

    /** CLI 노출 여부 단순 체크. */
    public static boolean isCliExposed(ToolExecutor executor) {
        return resolve(executor) == McpExposureLevel.CLI;
    }
}
