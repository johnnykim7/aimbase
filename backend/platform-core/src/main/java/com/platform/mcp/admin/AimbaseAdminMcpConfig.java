package com.platform.mcp.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
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

    /** CR-124 (SDK 2.0.0): SSE → Streamable HTTP 전환. 상세 사유는 ServerMcpConfig 참조. */
    @Bean
    public HttpServletStreamableServerTransportProvider adminMcpTransport(ObjectMapper objectMapper) {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(io.modelcontextprotocol.json.McpJsonDefaults.getMapper())
                .mcpEndpoint("/admin-mcp")
                .build();
    }

    /** CR-124: 2.0.0 provider 는 HttpServlet 상속체라 서블릿으로 등록한다. */
    @Bean
    public org.springframework.boot.web.servlet.ServletRegistrationBean<HttpServletStreamableServerTransportProvider>
            adminMcpServletRegistration(HttpServletStreamableServerTransportProvider adminMcpTransport) {
        var reg = new org.springframework.boot.web.servlet.ServletRegistrationBean<>(adminMcpTransport, "/admin-mcp/*");
        reg.setName("adminMcpStreamableServlet");
        reg.setAsyncSupported(true);
        reg.setLoadOnStartup(1);
        return reg;
    }

    @Bean
    public McpSyncServer adminMcpServer(HttpServletStreamableServerTransportProvider adminMcpTransport,
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
            String name, String description, Map<String, Object> inputSchema,
            Function<Map<String, Object>, String> handler) {
        // CR-124 (SDK 2.0.0): inputSchema 는 Map 오버로드 사용.
        var mcpTool = McpSchema.Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(inputSchema)
                .build();
        // CR-124 (SDK 2.0.0): 핸들러 2번째 인자가 Map → CallToolRequest 로 변경됨.
        return new McpServerFeatures.SyncToolSpecification(mcpTool, (exchange, request) -> {
            // MCP 메시지는 비동기 스레드에서 실행 → TenantContext가 없을 수 있음.
            // CR-125: 예전에는 "마지막으로 연결한 테넌트"를 전역에서 읽어 다른 테넌트의 DB로
            // 라우팅될 수 있었다. 이제 이 호출이 속한 MCP 세션의 테넌트만 조회한다.
            String sessionId = exchange == null ? null : exchange.sessionId();
            String sessionTenant = McpTenantSessionFilter.getTenantForSession(sessionId);
            boolean tenantSet = false;

            if (sessionTenant == null && com.platform.tenant.TenantContext.getTenantId() == null) {
                // 테넌트를 특정할 수 없으면 실행하지 않는다. 임의의 테넌트로 흘러드는 것보다
                // 명시적으로 거부하는 편이 안전하다(BIZ-003).
                log.warn("MCP tool '{}' rejected: no tenant bound to session {}", name, sessionId);
                return McpSchema.CallToolResult.builder()
                        .addTextContent("{\"error\":\"tenant not resolved for this MCP session; reconnect with tenant_id\"}")
                        .isError(true)
                        .build();
            }

            if (sessionTenant != null && com.platform.tenant.TenantContext.getTenantId() == null) {
                com.platform.tenant.TenantContext.setTenantId(sessionTenant);
                tenantSet = true;
                log.debug("MCP tool '{}': set tenant '{}' (session={})", name, sessionTenant, sessionId);
            }
            try {
                String result = handler.apply(request.arguments());
                return McpSchema.CallToolResult.builder()
                        .addTextContent(result)
                        .isError(false)
                        .build();
            } catch (Exception e) {
                log.error("MCP tool '{}' failed: {}", name, e.getMessage(), e);
                return McpSchema.CallToolResult.builder()
                        .addTextContent("{\"error\":\"" + e.getMessage() + "\"}")
                        .isError(true)
                        .build();
            } finally {
                if (tenantSet) {
                    com.platform.tenant.TenantContext.clear();
                }
            }
        });
    }

    // CR-124 (SDK 2.0.0): JsonSchema 레코드 대신 Map 으로 조립한다.
    private Map<String, Object> schema(Map<String, Object> properties) {
        return Map.of("type", "object", "properties", properties, "required", List.of());
    }

    private Map<String, Object> schemaReq(Map<String, Object> properties, List<String> required) {
        return Map.of("type", "object", "properties", properties, "required", required);
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
