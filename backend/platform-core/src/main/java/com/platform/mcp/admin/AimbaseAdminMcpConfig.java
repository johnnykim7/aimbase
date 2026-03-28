package com.platform.mcp.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.WebMvcSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * CR-020: Aimbase를 MCP Server로 노출.
 * 도메인팀이 Claude Desktop / MCP 클라이언트로 연결하여 관리 도구를 사용할 수 있음.
 * 엔드포인트: /admin-mcp/sse (SSE), /admin-mcp/message (메시지)
 */
@Configuration
public class AimbaseAdminMcpConfig {

    private static final Logger log = LoggerFactory.getLogger(AimbaseAdminMcpConfig.class);

    @Bean
    public WebMvcSseServerTransportProvider adminMcpTransport(ObjectMapper objectMapper) {
        return new WebMvcSseServerTransportProvider(objectMapper, "/admin-mcp/message", "/admin-mcp/sse");
    }

    @Bean
    public RouterFunction<ServerResponse> adminMcpRouterFunction(WebMvcSseServerTransportProvider adminMcpTransport) {
        return adminMcpTransport.getRouterFunction();
    }

    @Bean
    public McpSyncServer adminMcpServer(WebMvcSseServerTransportProvider adminMcpTransport,
                                         AdminToolService toolService) {
        String workflowRules = """

            ## 워크플로우 작성 규칙
            - steps 배열의 각 요소: {"id":"step_id", "type":"LLM_CALL|TOOL_USE|CONDITION|HUMAN_INPUT|SUB_WORKFLOW", "config":{...}, "depends_on":["step_id"]}
            - depends_on으로 DAG 구성 (순환 금지). 빈 배열이면 시작 스텝
            - type별 config:
              - LLM_CALL: {"connection_id":"...", "prompt_id":"...", "model":"..."}
              - TOOL_USE: {"tool_name":"...", "arguments":{...}}
              - CONDITION: {"expression":"...", "true_step":"...", "false_step":"..."}
              - HUMAN_INPUT: {"message":"...", "timeout_hours":24}
              - SUB_WORKFLOW: {"workflow_id":"..."}
            - trigger_config: {"type":"manual"} 또는 {"type":"webhook","path":"/hooks/..."} 또는 {"type":"schedule","cron":"..."}
            - error_handling: {"strategy":"stop_on_first"|"continue"|"retry", "max_retries":3}
            - 도구 호출 루프 최대 5회 (BIZ-001)
            """;

        var tools = List.of(
            // ── 조회 도구 (6개) ──
            tool("list_connections",
                "등록된 LLM 커넥션(Anthropic, OpenAI, Ollama 등) 목록을 조회합니다. 워크플로우에서 connection_id로 참조할 수 있습니다.",
                schema(Map.of()),
                toolService::listConnections),

            tool("list_knowledge_sources",
                "RAG 지식 소스 목록을 조회합니다. type 파라미터로 필터링 가능합니다.",
                schema(Map.of("type", propStr("소스 타입 필터 (file, web, database 등)"))),
                toolService::listKnowledgeSources),

            tool("list_prompts",
                "프롬프트 템플릿 목록을 조회합니다. domain 파라미터로 필터링 가능합니다.",
                schema(Map.of("domain", propStr("도메인 필터"))),
                toolService::listPrompts),

            tool("list_schemas",
                "JSON 스키마 목록을 조회합니다. 구조화된 출력(output_schema)에서 참조 가능합니다.",
                schema(Map.of()),
                toolService::listSchemas),

            tool("list_policies",
                "정책 목록을 조회합니다. 워크플로우 실행 시 자동 적용됩니다.",
                schema(Map.of()),
                toolService::listPolicies),

            tool("list_workflows",
                "워크플로우 목록을 조회합니다. domain 또는 project_id로 필터링 가능합니다.",
                schema(Map.of(
                    "domain", propStr("도메인 필터"),
                    "project_id", propStr("프로젝트 ID 필터"))),
                toolService::listWorkflows),

            // ── 워크플로우 CRUD 도구 (8개) ──
            tool("get_workflow",
                "워크플로우 상세 정보를 조회합니다.",
                schemaReq(Map.of("workflow_id", propStr("워크플로우 ID")),
                    List.of("workflow_id")),
                toolService::getWorkflow),

            tool("create_workflow",
                "새 워크플로우를 생성합니다." + workflowRules,
                schemaReq(Map.of(
                    "name", propStr("워크플로우 이름"),
                    "domain", propStr("도메인 (선택)"),
                    "project_id", propStr("프로젝트 ID (선택, 미지정 시 회사 공유)"),
                    "trigger_config", propObj("트리거 설정"),
                    "steps", propArray("DAG 스텝 배열"),
                    "error_handling", propObj("에러 처리 전략"),
                    "output_schema", propObj("출력 스키마 (선택)"),
                    "input_schema", propObj("입력 스키마 (선택, JSON Schema 형식으로 입력 파라미터 정의)")),
                    List.of("name", "steps")),
                toolService::createWorkflow),

            tool("update_workflow",
                "기존 워크플로우를 수정합니다. 변경할 필드만 전달하면 됩니다." + workflowRules,
                schemaReq(Map.of(
                    "workflow_id", propStr("수정할 워크플로우 ID"),
                    "name", propStr("새 이름"),
                    "trigger_config", propObj("새 트리거 설정"),
                    "steps", propArray("새 스텝 배열"),
                    "error_handling", propObj("새 에러 처리 전략"),
                    "output_schema", propObj("새 출력 스키마"),
                    "input_schema", propObj("새 입력 스키마 (JSON Schema 형식)")),
                    List.of("workflow_id")),
                toolService::updateWorkflow),

            tool("delete_workflow",
                "워크플로우를 삭제합니다. 삭제 후 복구 불가능합니다.",
                schemaReq(Map.of("workflow_id", propStr("삭제할 워크플로우 ID")),
                    List.of("workflow_id")),
                toolService::deleteWorkflow),

            tool("run_workflow",
                "워크플로우를 실행합니다. DAG가 비동기로 실행되며 즉시 run 객체가 반환됩니다.",
                schemaReq(Map.of(
                    "workflow_id", propStr("실행할 워크플로우 ID"),
                    "input", propObj("실행 입력 데이터 (선택)")),
                    List.of("workflow_id")),
                toolService::runWorkflow),

            tool("get_workflow_run",
                "워크플로우 실행 결과를 조회합니다. 각 스텝별 상태와 출력을 확인할 수 있습니다.",
                schemaReq(Map.of("run_id", propStr("실행 ID (UUID)")),
                    List.of("run_id")),
                toolService::getWorkflowRun),

            tool("list_workflow_runs",
                "워크플로우 실행 이력을 조회합니다.",
                schemaReq(Map.of(
                    "workflow_id", propStr("워크플로우 ID"),
                    "size", Map.of("type", "integer", "description", "조회 건수 (기본 10)")),
                    List.of("workflow_id")),
                toolService::listWorkflowRuns),

            tool("approve_workflow_run",
                "HUMAN_INPUT 스텝에서 대기 중인 실행을 승인하거나 거부합니다.",
                schemaReq(Map.of(
                    "run_id", propStr("실행 ID (UUID)"),
                    "approved", Map.of("type", "boolean", "description", "승인 여부"),
                    "reason", propStr("승인/거부 사유 (선택)")),
                    List.of("run_id", "approved")),
                toolService::approveWorkflowRun)
        );

        McpSyncServer server = McpServer.sync(adminMcpTransport)
                .serverInfo("aimbase-admin", "1.0.0")
                .tools(tools)
                .build();

        log.info("Aimbase Admin MCP Server started — {} tools registered at /admin-mcp/sse", tools.size());
        return server;
    }

    // ── Helper: Tool/Schema 빌더 ────────────────────────────

    private McpServerFeatures.SyncToolSpecification tool(
            String name, String description, McpSchema.JsonSchema inputSchema,
            Function<Map<String, Object>, String> handler) {
        var mcpTool = new McpSchema.Tool(name, description, inputSchema);
        return new McpServerFeatures.SyncToolSpecification(mcpTool, (exchange, args) -> {
            // MCP 메시지는 비동기 스레드에서 실행 → TenantContext가 없을 수 있음
            // McpTenantSessionFilter가 SSE 연결 시 저장한 테넌트를 사용
            String savedTenant = McpTenantSessionFilter.getCurrentMcpTenant();
            boolean tenantSet = false;
            if (savedTenant != null && com.platform.tenant.TenantContext.getTenantId() == null) {
                com.platform.tenant.TenantContext.setTenantId(savedTenant);
                tenantSet = true;
                log.debug("MCP tool '{}': set tenant '{}'", name, savedTenant);
            }
            try {
                String result = handler.apply(args);
                return new McpSchema.CallToolResult(result, false);
            } catch (Exception e) {
                log.error("MCP tool '{}' failed: {}", name, e.getMessage(), e);
                return new McpSchema.CallToolResult("{\"error\":\"" + e.getMessage() + "\"}", true);
            } finally {
                if (tenantSet) {
                    com.platform.tenant.TenantContext.clear();
                }
            }
        });
    }

    private McpSchema.JsonSchema schema(Map<String, Object> properties) {
        return new McpSchema.JsonSchema("object", properties, List.of(), null, null, null);
    }

    private McpSchema.JsonSchema schemaReq(Map<String, Object> properties, List<String> required) {
        return new McpSchema.JsonSchema("object", properties, required, null, null, null);
    }

    private Map<String, Object> propStr(String description) {
        return Map.of("type", "string", "description", description);
    }

    private Map<String, Object> propObj(String description) {
        return Map.of("type", "object", "description", description);
    }

    private Map<String, Object> propArray(String description) {
        return Map.of("type", "array", "description", description, "items", Map.of("type", "object"));
    }
}
