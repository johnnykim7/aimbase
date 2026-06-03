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

    /** CR 노출 레벨: CLI 두뇌(Claude CLI 등)가 호출 가능한 도구 (28개). */
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
            "todo_write"
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
