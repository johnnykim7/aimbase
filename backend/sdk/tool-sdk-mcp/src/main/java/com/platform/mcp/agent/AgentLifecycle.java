package com.platform.mcp.agent;

import com.platform.tool.SdkToolKit;
import com.platform.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * CR-041: Agent 전체 생명주기 관리.
 *
 * <pre>{@code
 * AgentConfig config = new AgentConfig("flowguard-agent", "http://aimbase:8181", "api-key", 8190, "/workspace");
 * AgentLifecycle agent = new AgentLifecycle(config);
 * agent.start();   // MCP 서버 기동 → STUN → Aimbase 등록 → 하트비트 시작
 * // ... 작업 대기 ...
 * agent.close();   // 하트비트 중지 → Aimbase 해제 → MCP 서버 중지
 * }</pre>
 */
public class AgentLifecycle implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentLifecycle.class);

    private final AgentConfig config;
    private final List<ToolExecutor> tools;
    private final AgentMcpServer mcpServer;
    private final AimbaseRegistrationClient registrationClient;
    private ScheduledExecutorService heartbeatScheduler;
    private String agentId;
    private String publicAddress;

    /**
     * 기본 SDK 도구로 Agent 생성.
     */
    public AgentLifecycle(AgentConfig config) {
        this(config, new SdkToolKit(config.workspaceBase()).getAllTools());
    }

    /**
     * 커스텀 도구 목록으로 Agent 생성.
     */
    public AgentLifecycle(AgentConfig config, List<ToolExecutor> tools) {
        this.config = config;
        this.tools = tools;
        this.mcpServer = new AgentMcpServer(tools, config.mcpPort());
        this.registrationClient = new AimbaseRegistrationClient(config.aimbaseUrl(), config.apiKey());
    }

    /**
     * Agent 시작: MCP 서버 기동 → STUN 주소 탐색 → Aimbase 등록 → 하트비트 시작.
     */
    public void start() {
        log.info("Starting agent '{}' on port {}...", config.agentName(), config.mcpPort());

        // 1. MCP 서버 기동
        mcpServer.start();

        // 2. STUN으로 공인 주소 탐색
        publicAddress = StunAddressResolver.discoverPublicAddress(
                config.stunServer(), config.stunPort());
        log.info("Discovered public address: {}", publicAddress);

        // 3. Aimbase에 등록
        List<String> toolNames = mcpServer.getToolNames();
        agentId = registrationClient.register(
                config.agentName(), publicAddress, config.mcpPort(),
                toolNames, Map.of("sdk_version", "1.0.0"));
        log.info("Registered with Aimbase: agentId={}", agentId);

        // 4. 하트비트 스레드 시작
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agent-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatScheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        registrationClient.heartbeat(agentId);
                    } catch (Exception e) {
                        log.warn("Heartbeat failed: {}", e.getMessage());
                    }
                },
                config.heartbeatIntervalMs(),
                config.heartbeatIntervalMs(),
                TimeUnit.MILLISECONDS
        );

        log.info("Agent '{}' started successfully — {} tools exposed via MCP",
                config.agentName(), toolNames.size());
    }

    /**
     * Agent 종료: 하트비트 중지 → Aimbase 해제 → MCP 서버 중지.
     */
    @Override
    public void close() {
        log.info("Stopping agent '{}'...", config.agentName());

        // 1. 하트비트 중지
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
            heartbeatScheduler = null;
        }

        // 2. Aimbase에서 해제
        if (agentId != null) {
            registrationClient.deregister(agentId);
            agentId = null;
        }

        // 3. MCP 서버 중지
        mcpServer.stop();

        log.info("Agent '{}' stopped", config.agentName());
    }

    public String getAgentId() {
        return agentId;
    }

    public String getPublicAddress() {
        return publicAddress;
    }

    public boolean isRunning() {
        return mcpServer.isRunning();
    }
}
