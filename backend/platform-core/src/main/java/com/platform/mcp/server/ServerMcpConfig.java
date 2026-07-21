package com.platform.mcp.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.mcp.agent.McpToolConversion;
import com.platform.tool.ToolExecutor;
import com.platform.tool.ToolRegistry;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
 *
 * <p><b>CR-121</b>: 도구 소스를 Spring 빈 목록({@code List<ToolExecutor>}) → {@link ToolRegistry} 로 교체하고
 * 기동 1회 등록 → 주기 양방향 동기화로 바꿨다. 원격 에이전트 도구({@code RemoteToolDiscovery} 가 30초 주기로
 * {@code toolRegistry.register()} 하는 {@code RemoteAgentToolExecutor})는 Spring 빈이 아니므로 이전 구현의
 * {@code allTools} 에 영영 들어오지 않았고, 그 결과 소비앱(bp-wes 등) 도구가 {@code /mcp/sse} 에 노출되지
 * 않았다 — 화이트리스트 추가로도 재기동으로도 풀 수 없는 구조적 누락이었다.</p>
 */
@Configuration
@ConditionalOnProperty(name = "mcp.server-exposure.enabled", havingValue = "true", matchIfMissing = true)
public class ServerMcpConfig {

    private static final Logger log = LoggerFactory.getLogger(ServerMcpConfig.class);

    private final ToolRegistry toolRegistry;
    private final ServerMcpToolDispatcher dispatcher;
    private final org.springframework.context.ApplicationContext applicationContext;

    /** 현재 MCP 서버에 실려 있는 도구 이름 — 주기 동기화의 델타 계산 기준. */
    private final Set<String> publishedTools = ConcurrentHashMap.newKeySet();

    public ServerMcpConfig(@org.springframework.context.annotation.Lazy ToolRegistry toolRegistry,
                            ServerMcpToolDispatcher dispatcher,
                            org.springframework.context.ApplicationContext applicationContext) {
        this.toolRegistry = toolRegistry;
        this.dispatcher = dispatcher;
        this.applicationContext = applicationContext;
    }

    /**
     * CR-124 (SDK 2.0.0): SSE → Streamable HTTP 전환.
     *
     * <p>{@code mcp-spring-webmvc} 아티팩트가 2.0.0 에 존재하지 않으며, SSE transport 는
     * 2.0.0 에서도 {@code 2024-11-05} 단일 버전만 광고해 Claude CLI 가 요구하는
     * {@code 2025-11-25} 협상이 불가하다. Streamable HTTP 는 4종 전부 지원한다.</p>
     */
    @Bean
    public HttpServletStreamableServerTransportProvider serverMcpTransport(ObjectMapper objectMapper) {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(io.modelcontextprotocol.json.McpJsonDefaults.getMapper())
                .mcpEndpoint("/mcp")
                .build();
    }

    /**
     * CR-124: 2.0.0 provider 는 {@link jakarta.servlet.http.HttpServlet} 상속체라
     * RouterFunction 이 아니라 서블릿으로 등록한다.
     */
    @Bean
    public org.springframework.boot.web.servlet.ServletRegistrationBean<HttpServletStreamableServerTransportProvider>
            serverMcpServletRegistration(HttpServletStreamableServerTransportProvider serverMcpTransport) {
        var reg = new org.springframework.boot.web.servlet.ServletRegistrationBean<>(serverMcpTransport, "/mcp/*");
        reg.setName("serverMcpStreamableServlet");
        reg.setAsyncSupported(true);
        reg.setLoadOnStartup(1);
        return reg;
    }

    /**
     * MCP 서버 빈. tool capabilities 를 미리 declare 하여 ApplicationReadyEvent 시점의
     * {@code addTool()} 호출이 정상 작동하게 한다 (SDK 0.10.0 제약).
     */
    @Bean
    public McpSyncServer serverMcpServer(HttpServletStreamableServerTransportProvider serverMcpTransport) {
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
     * <p>ToolRegistry.registerBuiltins 도 같은 {@link ApplicationReadyEvent} 를 듣고 LOWEST_PRECEDENCE
     * 로 뒤에 실행되므로 builtins 가 채워진 뒤 동작한다. 이 시점엔 원격 도구가 아직 없을 수 있으나
     * {@link #syncExposedTools()} 주기 동기화가 이후 반영한다.</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    @org.springframework.core.annotation.Order(org.springframework.core.Ordered.LOWEST_PRECEDENCE)
    public void registerExposedTools(ApplicationReadyEvent event) {
        // CR-121: ToolRegistry.registerBuiltins 도 같은 이벤트를 동순위로 듣기 때문에 실행 순서가
        // 비결정적이다. 아직 안 돌았다면 builtinNames 가 비어 노출 판정이 builtin 을 외부 도구로
        // 오판하므로(화이트리스트 우회) 여기서 등록하지 않고 주기 동기화에 맡긴다.
        if (!toolRegistry.isBuiltinsRegistered()) {
            log.info("Server MCP: builtins not registered yet — deferring tool exposure to periodic sync");
            return;
        }
        int published = syncOnce();
        log.info("Server MCP endpoint exposing {} CLI-level tool(s): {}", published, publishedTools);
    }

    /**
     * CR-121: ToolRegistry ↔ MCP 서버 도구 목록 주기 양방향 동기화.
     *
     * <p>{@code RemoteToolDiscovery}(BIZ-080, 30초 주기)가 원격 도구를 등록/해제한 뒤 그 결과가
     * {@code /mcp/sse} 에 반영되도록 같은 주기로 델타를 적용한다. 신규는 {@code addTool},
     * 사라진 것은 {@code removeTool}. 변경이 있을 때만 {@code notifyToolsListChanged} 로
     * 접속 중인 CLI 클라이언트에 목록 갱신을 알린다.</p>
     *
     * <p>{@code initialDelay} 를 RemoteToolDiscovery(10초)보다 뒤인 15초로 두어 첫 원격 동기화
     * 결과를 곧바로 싣는다.</p>
     */
    @Scheduled(fixedRate = 30_000, initialDelay = 15_000)
    public void syncExposedTools() {
        try {
            syncOnce();
        } catch (Exception e) {
            log.warn("Server MCP tool sync failed: {}", e.getMessage());
        }
    }

    /**
     * 현재 ToolRegistry 상태를 MCP 서버에 반영하고, 반영 후 노출 도구 수를 반환.
     *
     * <p>CR-121: builtin 등록 전에는 아무것도 하지 않는다 — 그 시점의 노출 판정은 builtin 을 외부
     * 도구로 오판해 화이트리스트를 우회시킨다(운영 실측 회귀).</p>
     */
    private synchronized int syncOnce() {
        if (!toolRegistry.isBuiltinsRegistered()) {
            return publishedTools.size();
        }
        McpSyncServer mcpServer = applicationContext.getBean("serverMcpServer", McpSyncServer.class);

        Map<String, ToolExecutor> desired = new HashMap<>();
        for (ToolExecutor tool : toolRegistry.getCliExposedTools()) {
            desired.put(tool.getDefinition().name(), tool);
        }

        boolean changed = false;

        // 신규 노출 도구 추가
        for (Map.Entry<String, ToolExecutor> entry : desired.entrySet()) {
            String name = entry.getKey();
            if (publishedTools.contains(name)) {
                continue;
            }
            ToolExecutor tool = entry.getValue();
            try {
                McpSchema.Tool mcpTool = McpToolConversion.toMcpTool(tool);
                // CR-124 (SDK 2.0.0): 핸들러 2번째 인자가 Map → CallToolRequest 로 변경됨.
                mcpServer.addTool(new McpServerFeatures.SyncToolSpecification(mcpTool,
                        (exchange, request) -> dispatcher.dispatch(tool, name, request.arguments())));
                publishedTools.add(name);
                changed = true;
                log.info("Server MCP: tool exposed '{}'", name);
            } catch (Exception e) {
                log.warn("Server MCP: failed to register tool '{}': {}", name, e.getMessage());
            }
        }

        // 더 이상 노출 대상이 아닌 도구 제거 (에이전트 해제 / 화이트리스트에서 제외)
        for (String name : new ArrayList<>(publishedTools)) {
            if (desired.containsKey(name)) {
                continue;
            }
            try {
                mcpServer.removeTool(name);
                publishedTools.remove(name);
                changed = true;
                log.info("Server MCP: tool unexposed '{}'", name);
            } catch (Exception e) {
                // 제거 실패 시에도 추적 집합에서 빼야 재시도 루프에 갇히지 않는다.
                publishedTools.remove(name);
                log.warn("Server MCP: failed to remove tool '{}': {}", name, e.getMessage());
            }
        }

        if (changed) {
            try {
                mcpServer.notifyToolsListChanged();
            } catch (Exception e) {
                log.debug("Server MCP: tools/list_changed notification failed: {}", e.getMessage());
            }
        }
        return publishedTools.size();
    }

}
