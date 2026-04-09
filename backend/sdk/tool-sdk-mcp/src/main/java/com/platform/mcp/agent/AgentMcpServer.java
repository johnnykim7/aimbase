package com.platform.mcp.agent;

import com.platform.tool.ToolExecutor;
import com.platform.tool.model.UnifiedToolDef;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
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
            // Build tool specifications list
            List<McpServerFeatures.SyncToolSpecification> toolSpecs = new ArrayList<>();
            if (toolExecutors != null) {
                for (ToolExecutor tool : toolExecutors) {
                    UnifiedToolDef def = tool.getDefinition();
                    var mcpTool = new McpSchema.Tool(def.name(), def.description(),
                            toJsonSchema(def.inputSchema()));
                    toolSpecs.add(new McpServerFeatures.SyncToolSpecification(mcpTool,
                            (exchange, args) -> {
                                try {
                                    String result = tool.execute(args);
                                    return new McpSchema.CallToolResult(result, false);
                                } catch (Exception e) {
                                    return new McpSchema.CallToolResult(
                                            "{\"error\":\"" + e.getMessage() + "\"}", true);
                                }
                            }));
                }
            }

            return McpServer.sync(transport)
                    .serverInfo("aimbase-agent", "1.0.0")
                    .tools(toolSpecs)
                    .build();
        }

        @SuppressWarnings("unchecked")
        private static McpSchema.JsonSchema toJsonSchema(Map<String, Object> schema) {
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
