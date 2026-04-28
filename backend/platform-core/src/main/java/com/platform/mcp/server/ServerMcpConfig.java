package com.platform.mcp.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.mcp.agent.McpToolConversion;
import com.platform.tool.McpExposureLevel;
import com.platform.tool.ToolExecutor;
import com.platform.tool.ToolRegistry;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.WebMvcSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.List;

/**
 * CR-072: 서버 측 MCP endpoint(/mcp/sse, /mcp/message) 구성.
 *
 * <p>{@code mcp.server-exposure.enabled=true} (기본값) 일 때만 활성화.
 * Spring Boot 메인 컨텍스트에 통합되어 별도 인스턴스를 띄우지 않는다 — CR-073 방향성.</p>
 *
 * <p>인증/테넌트 라우팅은 {@code SecurityFilterChain} 의 {@code ApiKeyAuthenticationFilter}
 * 와 {@code AgentIdRequestFilter} 가 담당. 이 클래스는 도구 노출/실행만 책임진다.</p>
 *
 * <p>도구 등록은 {@link ApplicationReadyEvent} 이후 — ToolRegistry 의 builtins 가 모두 들어온 뒤.
 * MCP 빈 자체는 빌드 시점 빈 상태로 시작 후, 이벤트 시점에 도구 사양 추가.</p>
 */
@Configuration
@ConditionalOnProperty(name = "mcp.server-exposure.enabled", havingValue = "true", matchIfMissing = true)
public class ServerMcpConfig {

    private static final Logger log = LoggerFactory.getLogger(ServerMcpConfig.class);

    private final List<ToolExecutor> allTools;
    private final ServerMcpToolDispatcher dispatcher;
    private final org.springframework.context.ApplicationContext applicationContext;

    public ServerMcpConfig(@org.springframework.context.annotation.Lazy List<ToolExecutor> allTools,
                            ServerMcpToolDispatcher dispatcher,
                            org.springframework.context.ApplicationContext applicationContext) {
        this.allTools = allTools;
        this.dispatcher = dispatcher;
        this.applicationContext = applicationContext;
    }

    @Bean
    public WebMvcSseServerTransportProvider serverMcpTransport(ObjectMapper objectMapper) {
        return new WebMvcSseServerTransportProvider(objectMapper, "/mcp/message", "/mcp/sse");
    }

    @Bean
    public RouterFunction<ServerResponse> serverMcpRouterFunction(WebMvcSseServerTransportProvider serverMcpTransport) {
        return serverMcpTransport.getRouterFunction();
    }

    /**
     * MCP 서버 빈. tool capabilities 를 미리 declare 하여 ApplicationReadyEvent 시점의
     * {@code addTool()} 호출이 정상 작동하게 한다 (SDK 0.10.0 제약).
     */
    @Bean
    public McpSyncServer serverMcpServer(WebMvcSseServerTransportProvider serverMcpTransport) {
        return McpServer.sync(serverMcpTransport)
                .serverInfo("aimbase-server", "1.0.0")
                .capabilities(io.modelcontextprotocol.spec.McpSchema.ServerCapabilities.builder()
                        .tools(true)
                        .build())
                .build();
    }

    /**
     * ToolRegistry 가 builtins 등록을 마친 이후, MCP 서버에 도구 사양을 동적으로 추가.
     *
     * <p>{@code addTool} 은 0.10.0 SDK 에서 지원. 같은 이름이 이미 있으면 무시하거나
     * 예외를 던지므로 중복 등록은 발생하지 않도록 한 번만 호출.</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    @org.springframework.core.annotation.Order(org.springframework.core.Ordered.LOWEST_PRECEDENCE)
    public void registerExposedTools(ApplicationReadyEvent event) {
        McpSyncServer mcpServer = applicationContext.getBean("serverMcpServer", McpSyncServer.class);
        List<ToolExecutor> exposed = allTools.stream()
                .filter(t -> McpExposurePolicy.resolve(t) == McpExposureLevel.CLI)
                .toList();
        int added = 0;
        for (ToolExecutor tool : exposed) {
            String name = tool.getDefinition().name();
            McpSchema.Tool mcpTool = McpToolConversion.toMcpTool(tool);
            try {
                mcpServer.addTool(new McpServerFeatures.SyncToolSpecification(mcpTool,
                        (exchange, args) -> dispatcher.dispatch(tool, name, args)));
                added++;
            } catch (Exception e) {
                log.warn("Server MCP: failed to register tool '{}': {}", name, e.getMessage());
            }
        }
        log.info("Server MCP endpoint exposing {} CLI-level tool(s): {}",
                added,
                exposed.stream().map(t -> t.getDefinition().name()).toList());
    }

}
