package com.platform.mcp.server;

import com.platform.config.PlatformSettingsService;
import com.platform.tool.EnhancedToolExecutor;
import com.platform.tool.McpExposureLevel;
import com.platform.tool.ToolContractMeta;
import com.platform.tool.ToolExecutor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * CR-072: 서버 도구의 MCP 노출 정책.
 * CR-110: 하드코딩 화이트리스트 → 런타임 설정({@code global_config}) 단일 소스로 외부화.
 *
 * <p>도구별 {@link McpExposureLevel} 결정 우선순위:
 * <ol>
 *   <li>도구가 {@link ToolContractMeta#mcpExposureLevel()} 로 명시 선언한 값 (기본 NONE 외)
 *       — 미래 도구가 {@code withMcpExposure(CLI)} 선언 시 설정보다 우선 (현재 선언 도구 0개, 메커니즘만 보존)</li>
 *   <li>런타임 설정 화이트리스트 {@code mcp.cli-exposed-tools} (CSV) — 단일 소스</li>
 *   <li>기본값 {@link McpExposureLevel#NONE}</li>
 * </ol>
 *
 * <p><b>CR-110 단일 소스 불변식</b>: CLI 노출 도구 집합은 {@code mcp.cli-exposed-tools} 설정
 * (V66 seed) 이 단일 소스다. {@code ServerMcpConfig}(/mcp/sse 노출)·{@code ServerMcpToolDispatcher}
 * (실행 게이트)·{@code ToolRegistry.getToolDefs(filter)}(API 어댑터가 모델에 싣는 목록) 가 모두
 * 이 정책({@link #resolve})을 코드로 참조하므로 CLI 경로와 API 경로의 도구 집합이 구조적으로 동일하다.
 * (CR-104 이전엔 두 목록 출처 불일치가 "No such tool" 회귀의 근본 원인이었고, 주석으로만 강제했었다.)</p>
 *
 * <p>{@code parse_document} 는 양쪽 도구 목록에서 의도적으로 제외한다 — PDF/문서는 CLI/모델이
 * 비전으로 직접 읽고, 사이드카 파싱은 {@code MCPRagClient.parseDocument}(첨부 자동 파싱 등) 직접
 * 호출 경로로만 사용한다(도구 레지스트리 무관).</p>
 *
 * <p>설정 row 부재/blank 시 {@link #DEFAULT_FALLBACK} 으로 안전 폴백(기존 동작 보존, fail-safe).
 * {@link #DEFAULT_FALLBACK} 은 V66 seed CSV 와 정확히 동일 집합이어야 한다.</p>
 */
@Component
public class McpExposurePolicy {

    /** 런타임 설정 키 — CLI 노출 도구 화이트리스트 (CSV). */
    public static final String SETTING_KEY = "mcp.cli-exposed-tools";

    /**
     * 설정 부재 시 안전 폴백 (fail-safe). V66 seed CSV 와 동일한 42개.
     * 내부 전용(team_create / enter_plan_mode / exit_plan_mode / verify_plan_execution /
     * temp_cleanup / team_delete) 6개와 {@code parse_document} 는 의도적으로 제외 — 화이트리스트에 없으면 NONE.
     */
    static final List<String> DEFAULT_FALLBACK = List.of(
            // Network
            "web_search", "http_request",
            // Collaboration
            "send_message", "send_notification",
            // AI / Content
            "brief", "analyze_image", "translate_text", "suggest_background_pr",
            // OCR (CR-092)
            "ocr_image",
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
            // Shell / 파일 쓰기 / 다운로드
            "bash", "file_write", "download_file",
            // Native 파일 탐색·읽기·편집 (tool-sdk-core nativetool)
            "builtin_grep", "builtin_file_read", "builtin_glob", "builtin_safe_edit",
            "builtin_patch_apply", "builtin_structured_search", "builtin_document_section_read",
            "builtin_path_info", "builtin_workspace_snapshot",
            // 유틸
            "zip_extract", "calculate", "get_current_time"
    );

    private final PlatformSettingsService settings;

    public McpExposurePolicy(PlatformSettingsService settings) {
        this.settings = settings;
    }

    /** 도구의 노출 레벨 결정. 메타가 명시했으면 그게 우선, 없으면 설정 화이트리스트, 그것도 없으면 NONE. */
    public McpExposureLevel resolve(ToolExecutor executor) {
        if (executor instanceof EnhancedToolExecutor enhanced) {
            ToolContractMeta meta = enhanced.getContractMeta();
            if (meta != null && meta.mcpExposureLevel() != McpExposureLevel.NONE) {
                return meta.mcpExposureLevel();
            }
        }
        String name = executor.getDefinition().name();
        return cliExposedNames().contains(name) ? McpExposureLevel.CLI : McpExposureLevel.NONE;
    }

    /** CLI 노출 여부 단순 체크. */
    public boolean isCliExposed(ToolExecutor executor) {
        return resolve(executor) == McpExposureLevel.CLI;
    }

    /** 현재 유효한 CLI 노출 화이트리스트 (설정 우선, 부재 시 폴백). */
    private List<String> cliExposedNames() {
        return settings.getStringList(SETTING_KEY, DEFAULT_FALLBACK);
    }
}
