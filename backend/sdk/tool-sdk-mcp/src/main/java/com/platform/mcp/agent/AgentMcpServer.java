package com.platform.mcp.agent;

import com.platform.tool.McpResultTruncator;
import com.platform.tool.ToolExecutor;
import com.platform.tool.model.UnifiedToolDef;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.server.transport.WebMvcSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CR-041: SDK 도구를 MCP 서버로 노출.
 * 소비앱이 이 클래스를 사용해 자신의 도구를 MCP 프로토콜로 제공한다.
 * Aimbase 서버가 필요 시 MCP 클라이언트로 연결하여 도구를 호출한다.
 */
public class AgentMcpServer {

    private static final Logger log = LoggerFactory.getLogger(AgentMcpServer.class);

    private final List<ToolExecutor> tools;
    private final int port;
    private final Map<String, ToolExecutor> toolMap = new ConcurrentHashMap<>();
    private ConfigurableApplicationContext appContext;

    public AgentMcpServer(List<ToolExecutor> tools, int port) {
        this.tools = tools;
        this.port = port;
        tools.forEach(t -> toolMap.put(t.getDefinition().name(), t));
    }

    /**
     * MCP 서버 시작. 내장 Spring Boot 서버를 기동한다.
     */
    public void start() {
        SpringApplication app = new SpringApplication(AgentMcpServerApp.class);
        Map<String, Object> props = new HashMap<>();
        props.put("server.port", port);
        props.put("spring.main.web-application-type", "servlet");
        props.put("spring.main.banner-mode", "off");
        app.setDefaultProperties(props);

        AgentMcpServerApp.setToolExecutors(tools);

        appContext = app.run();
        log.info("Agent MCP server started on port {}, exposing {} tools", port, tools.size());
    }

    /**
     * MCP 서버 중지.
     */
    public void stop() {
        if (appContext != null) {
            appContext.close();
            appContext = null;
            log.info("Agent MCP server stopped");
        }
    }

    public boolean isRunning() {
        return appContext != null && appContext.isActive();
    }

    public int getPort() {
        return port;
    }

    public List<String> getToolNames() {
        return tools.stream().map(t -> t.getDefinition().name()).toList();
    }

    /**
     * CR-044 PRD-282: stdio 모드로 MCP 서버를 실행한다.
     *
     * Claude CLI가 --mcp-config로 이 jar를 자식 프로세스로 기동할 때 사용.
     * stdin/stdout을 MCP 채널로 사용하므로 SSE 타이밍 이슈 없음.
     * 이 메서드는 프로세스가 종료될 때까지 블로킹된다.
     *
     * 진입점: AimbaseStdioMcpMain.main() 또는 --mcp-stdio 플래그
     */
    public void startStdio() {
        log.info("AgentMcpServer stdio 모드 시작: {} 도구 노출", tools.size());
        McpSyncServer server = buildMcpServer(new StdioServerTransportProvider());
        // stdio transport는 stdin이 닫힐 때까지 블로킹 처리한다.
        // JVM 종료 시그널(SIGTERM/SIGINT)에 graceful 종료 등록
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("AgentMcpServer stdio 종료 중...");
            server.closeGracefully();
        }, "aimbase-stdio-mcp-shutdown"));
        // Claude CLI가 자식 프로세스를 종료할 때까지 메인 스레드 대기
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("AgentMcpServer stdio 인터럽트 수신, 종료");
        }
    }

    /**
     * 공통 도구 스펙 빌더 — SSE/stdio 모두 동일한 도구 목록을 노출한다.
     */
    private McpSyncServer buildMcpServer(io.modelcontextprotocol.spec.McpServerTransportProvider transport) {
        List<McpServerFeatures.SyncToolSpecification> toolSpecs = new ArrayList<>();
        for (ToolExecutor tool : tools) {
            UnifiedToolDef def = tool.getDefinition();
            var mcpTool = new McpSchema.Tool(def.name(), def.description(),
                    AgentMcpServerApp.toJsonSchemaStatic(def.inputSchema()));
            toolSpecs.add(new McpServerFeatures.SyncToolSpecification(mcpTool,
                    (exchange, args) -> dispatch(tool, def.name(), args)));
        }
        return McpServer.sync(transport)
                .serverInfo("aimbase-agent", "1.0.0")
                .tools(toolSpecs)
                .build();
    }

    /**
     * 도구 호출 디스패치 — 단위 테스트에서 직접 검증할 수 있도록 패키지-private 헬퍼로 분리.
     *
     * <p>CR-067: EnhancedToolExecutor 의 default bridge 가 ToolResultRenderer 로 본문을 직렬화하므로
     * 별도 분기 없이 {@code tool.execute(args)} 한 줄로 본문이 정상 노출된다.
     * 그 후 {@link McpResultTruncator} 로 길이 제한 적용. 예외는 isError=true 로 패킹.
     */
    static McpSchema.CallToolResult dispatch(ToolExecutor tool, String name, Map<String, Object> args) {
        try {
            String result = tool.execute(args);
            result = McpResultTruncator.truncate(name, result);
            return new McpSchema.CallToolResult(result, false);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new McpSchema.CallToolResult(
                    "{\"error\":\"" + msg.replace("\"", "\\\"") + "\"}", true);
        }
    }

    /**
     * 내장 Spring Boot 앱 — MCP SSE 엔드포인트 제공.
     */
    @SpringBootApplication(scanBasePackages = "com.platform.mcp.agent.internal")
    static class AgentMcpServerApp {

        private static List<ToolExecutor> toolExecutors;

        static void setToolExecutors(List<ToolExecutor> tools) {
            toolExecutors = tools;
        }

        @Bean
        public WebMvcSseServerTransportProvider mcpTransport() {
            return new WebMvcSseServerTransportProvider(
                    new com.fasterxml.jackson.databind.ObjectMapper(),
                    "/mcp/message",
                    "/mcp/sse"
            );
        }

        @Bean
        public RouterFunction<ServerResponse> mcpRouterFunction(WebMvcSseServerTransportProvider transport) {
            return transport.getRouterFunction();
        }

        @Bean
        public McpSyncServer mcpServer(WebMvcSseServerTransportProvider transport) {
            // SSE 모드: 외부 AgentMcpServer 인스턴스의 공통 빌더 재사용
            // toolExecutors가 없으면(직접 Spring Boot 기동 시) 빈 서버로 대기
            if (toolExecutors == null || toolExecutors.isEmpty()) {
                return McpServer.sync(transport).serverInfo("aimbase-agent", "1.0.0").build();
            }
            AgentMcpServer outer = new AgentMcpServer(toolExecutors, 0);
            return outer.buildMcpServer(transport);
        }

        @SuppressWarnings("unchecked")
        static McpSchema.JsonSchema toJsonSchemaStatic(Map<String, Object> schema) {
            if (schema == null) {
                return new McpSchema.JsonSchema("object", Map.of(), List.of(), null, null, null);
            }
            String type = (String) schema.getOrDefault("type", "object");
            Map<String, Object> properties = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
            List<String> required = (List<String>) schema.getOrDefault("required", List.of());
            Boolean additionalProperties = (Boolean) schema.get("additionalProperties");
            return new McpSchema.JsonSchema(type, properties, required, additionalProperties, null, null);
        }
    }
}
