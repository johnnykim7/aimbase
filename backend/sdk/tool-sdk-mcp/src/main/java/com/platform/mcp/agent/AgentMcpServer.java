package com.platform.mcp.agent;

import com.platform.tool.McpResultTruncator;
import com.platform.tool.ToolExecutor;
import com.platform.tool.model.UnifiedToolDef;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
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
        // CR-074: 부모 SpringApplication 의 -Dserver.port 시스템 프로퍼티가 자식 컨텍스트에도
        // 우선 적용되어 포트 충돌 → setDefaultProperties 만으로는 무력. command-line args 로 박아
        // SpringApplication 우선순위 최상단에 둔다.
        String[] cliArgs = new String[] { "--server.port=" + port };
        Map<String, Object> props = new HashMap<>();
        props.put("server.port", port);
        props.put("spring.main.web-application-type", "servlet");
        props.put("spring.main.banner-mode", "off");
        // CR-072: AgentMcpServerApp 의 @ConditionalOnProperty 활성화 (자체 모드로 띄울 때만).
        props.put("aimbase.agent.mcp-sse.enabled", "true");
        // CR-074: 부모 컨텍스트(AimbaseAgentApplication) 가 끌고 들어온 platform-core 의 전이
        // autoconfig (JPA/Flyway/Redis/Spring AI) 가 자식 SpringApplication 에서도 시도되어
        // DataSource 미구성으로 부팅 실패. 자식 컨텍스트에서도 동일하게 차단.
        props.put("spring.autoconfigure.exclude", String.join(",",
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
                "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
                "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
                "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration",
                "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
                "org.springframework.boot.autoconfigure.batch.BatchAutoConfiguration",
                "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration",
                "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration",
                "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration",
                "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration",
                "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration",
                "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration",
                "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration",
                "org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration",
                "org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration",
                "org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration"
        ));
        app.setDefaultProperties(props);

        AgentMcpServerApp.setToolExecutors(tools);

        appContext = app.run(cliArgs);
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
        // SDK 0.17.0: StdioServerTransportProvider 는 McpJsonMapper 인자 필요.
        McpSyncServer server = buildMcpServer(
                new StdioServerTransportProvider(io.modelcontextprotocol.json.McpJsonDefaults.getMapper()));
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
     * 공통 도구 스펙 목록 — Streamable HTTP/stdio 모두 동일한 도구 목록을 노출한다.
     *
     * <p>CR-124 (SDK 2.0.0): {@code McpStreamableServerTransportProvider} 와
     * {@code McpServerTransportProvider} 가 서로 다른 인터페이스로 분리되어
     * {@code McpServer.sync(...)} 오버로드가 갈렸다. 공통분모를 "도구 스펙 목록"으로 내리고
     * 서버 조립은 각 transport 쪽에서 수행한다.</p>
     */
    private List<McpServerFeatures.SyncToolSpecification> buildToolSpecs() {
        List<McpServerFeatures.SyncToolSpecification> toolSpecs = new ArrayList<>();
        for (ToolExecutor tool : tools) {
            UnifiedToolDef def = tool.getDefinition();
            var mcpTool = McpToolConversion.toMcpTool(tool);
            // CR-124 (SDK 2.0.0): 핸들러 2번째 인자가 Map → CallToolRequest 로 변경됨.
            toolSpecs.add(new McpServerFeatures.SyncToolSpecification(mcpTool,
                    (exchange, request) -> dispatch(tool, def.name(), request.arguments())));
        }
        return toolSpecs;
    }

    /** stdio transport 용 서버 조립. */
    private McpSyncServer buildMcpServer(io.modelcontextprotocol.spec.McpServerTransportProvider transport) {
        return McpServer.sync(transport)
                .serverInfo("aimbase-agent", "1.0.0")
                .tools(buildToolSpecs())
                .build();
    }

    /** Streamable HTTP transport 용 서버 조립 (CR-124). */
    private McpSyncServer buildMcpServer(io.modelcontextprotocol.spec.McpStreamableServerTransportProvider transport) {
        return McpServer.sync(transport)
                .serverInfo("aimbase-agent", "1.0.0")
                .tools(buildToolSpecs())
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
        return McpToolConversion.dispatch(tool, name, args);
    }

    /**
     * 내장 Spring Boot 앱 — MCP SSE 엔드포인트 제공.
     *
     * <p>CR-072 (2026-04-28): {@code @ConditionalOnProperty} 로 가드 추가. 이 nested 클래스 자체가
     * {@code @SpringBootApplication} 이라 platform-core 의 component-scan 에 끌려와서 빈이 등록되며
     * platform-core 의 {@code ServerMcpConfig.serverMcpTransport} 와 경로 (/mcp/sse) 충돌을 일으켰다.
     * agent 가 SSE 모드를 명시적으로 띄우지 않으면 활성화되지 않도록 한다.</p>
     */
    @SpringBootApplication(scanBasePackages = "com.platform.mcp.agent.internal")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "aimbase.agent.mcp-sse.enabled", havingValue = "true")
    static class AgentMcpServerApp {

        private static List<ToolExecutor> toolExecutors;

        static void setToolExecutors(List<ToolExecutor> tools) {
            toolExecutors = tools;
        }

        @Bean
        public HttpServletStreamableServerTransportProvider mcpTransport() {
            // CR-124 (SDK 2.0.0): WebMvcSse 제거(mcp-spring-webmvc 아티팩트 자체가 2.0.0 에 없음).
            // Streamable HTTP 단일 엔드포인트로 전환 — SSE 는 2.0.0 에서도 2024-11-05 만 광고해
            // Claude CLI 가 요구하는 2025-11-25 협상이 불가하다.
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
                mcpServletRegistration(HttpServletStreamableServerTransportProvider transport) {
            var reg = new org.springframework.boot.web.servlet.ServletRegistrationBean<>(transport, "/mcp/*");
            reg.setName("agentMcpStreamableServlet");
            reg.setAsyncSupported(true);
            reg.setLoadOnStartup(1);
            return reg;
        }

        @Bean
        public McpSyncServer mcpServer(HttpServletStreamableServerTransportProvider transport) {
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
