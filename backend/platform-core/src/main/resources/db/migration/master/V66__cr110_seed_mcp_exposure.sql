-- CR-110: CLI/API 도구 노출 목록 하드코딩 → 런타임 설정 단일 소스 외부화
-- McpExposurePolicy 의 하드코딩 화이트리스트(CLI_EXPOSED Set.of)를 global_config 로 외부화.
-- PlatformSettingsService.getStringList("mcp.cli-exposed-tools", DEFAULT_FALLBACK) 가 5분 캐시로 조회.
--
-- 단일 소스 불변식: 이 CSV 가 CLI 노출 도구의 단일 소스다. ServerMcpConfig(/mcp/sse 노출)·
-- ServerMcpToolDispatcher(실행 게이트)·ToolRegistry.getToolDefs(filter)(API 어댑터가 모델에 싣는 목록)
-- 가 모두 McpExposurePolicy.resolve() 를 코드로 참조하므로 CLI 경로와 API 경로의 도구 집합이 동일하다.
--
-- parse_document 는 의도적으로 제외 — PDF/문서는 모델/CLI 가 비전으로 직접 읽고, 사이드카 파싱은
-- MCPRagClient.parseDocument 직접 호출 경로(첨부 자동 파싱 등)로만 사용한다(도구 레지스트리 무관).
-- 내부 전용 6개(team_create/team_delete/enter_plan_mode/exit_plan_mode/verify_plan_execution/temp_cleanup)
-- 도 화이트리스트에 없으므로 NONE.
--
-- 값은 McpExposurePolicy.DEFAULT_FALLBACK(코드 상수) 과 정확히 동일한 42개여야 한다 — 환경별 도구집합 갈림 방지.

INSERT INTO global_config (config_key, config_value, description, is_encrypted, updated_by, updated_at)
VALUES
    ('mcp.cli-exposed-tools',
     'web_search,http_request,send_message,send_notification,brief,analyze_image,translate_text,suggest_background_pr,ocr_image,notebook_edit,read_tool_result,lsp,skill_invoke,tool_search,list_mcp_resources,read_mcp_resource,remote_trigger,schedule_cron,cron_list,cron_delete,task_create,task_get,task_list,task_update,task_output,task_stop,todo_write,bash,file_write,download_file,builtin_grep,builtin_file_read,builtin_glob,builtin_safe_edit,builtin_patch_apply,builtin_structured_search,builtin_document_section_read,builtin_path_info,builtin_workspace_snapshot,zip_extract,calculate,get_current_time',
     'CR-110: CLI/API 노출 도구 화이트리스트 (CSV, 단일 소스). builtin 서버 도구 중 CLI/모델에 노출할 것. parse_document 및 내부전용 6개는 의도적 제외. 제거는 런타임 즉시 반영, 추가는 재기동 시 /mcp/sse 반영.',
     false, 'system', NOW())
ON CONFLICT (config_key) DO NOTHING;
