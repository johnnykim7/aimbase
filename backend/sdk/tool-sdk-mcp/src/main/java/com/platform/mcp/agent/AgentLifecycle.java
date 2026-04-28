package com.platform.mcp.agent;

import com.platform.tool.SdkToolKit;
import com.platform.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * CR-041: Agent 전체 생명주기 관리.
 *
 * <p>CR-074: TURN-TCP NAT 우회 통합 — config.turnEnabled() 가 true 면
 * TCP allocate → relay 주소를 {@code metadata.runnerEndpoint} 로 등록 →
 * {@link TurnConnectionBindHandler} + {@link TurnLoopbackBridge} 가동.
 *
 * <pre>{@code
 * AgentConfig config = new AgentConfig("flowguard-agent", "http://aimbase:8181", "api-key", 8190, "/workspace");
 * AgentLifecycle agent = new AgentLifecycle(config);
 * agent.start();   // MCP 서버 기동 → STUN → (TURN-TCP) → Aimbase 등록 → 하트비트 시작
 * // ... 작업 대기 ...
 * agent.close();   // 하트비트 중지 → Aimbase 해제 → MCP 서버 중지 → TURN 자원 정리
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
    private String turnRelayAddress;

    // CR-074
    private TurnConnectionBindHandler bindHandler;
    private TurnLoopbackBridge loopbackBridge;
    private ScheduledExecutorService turnRefreshScheduler;

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
        this(config, tools, null);
    }

    /**
     * CR-074: tenantId 명시 — Aimbase BE 가 X-Tenant-Id 강제할 때 사용.
     */
    public AgentLifecycle(AgentConfig config, List<ToolExecutor> tools, String tenantId) {
        this.config = config;
        this.tools = tools;
        this.mcpServer = new AgentMcpServer(tools, config.mcpPort());
        this.registrationClient = new AimbaseRegistrationClient(
                config.aimbaseUrl(), config.apiKey(), tenantId);
    }

    /**
     * Agent 시작: MCP 서버 기동 → STUN 주소 탐색 → (TURN-TCP) → Aimbase 등록 → 하트비트 시작.
     */
    public void start() {
        log.info("Starting agent '{}' on port {}...", config.agentName(), config.mcpPort());

        // 1. MCP 서버 기동
        mcpServer.start();

        // 2. STUN으로 공인 주소 탐색
        publicAddress = StunAddressResolver.discoverPublicAddress(
                config.stunServer(), config.stunPort());
        log.info("Discovered public address: {}", publicAddress);

        // 3. TURN 설정
        Map<String, Object> metadata = new HashMap<>(Map.of("sdk_version", "1.0.0"));
        if (config.turnEnabled() && config.turnTransport() == AgentConfig.TurnTransport.TCP) {
            startTurnTcp(metadata);
        } else if (config.turnServer() != null && !config.turnServer().isBlank()
                && config.turnTransport() == AgentConfig.TurnTransport.UDP) {
            // 후방호환: 명시적으로 UDP 전송 선택 시 — UDP relay 주소 metadata 등록 (사용처 향후 정리)
            turnRelayAddress = TurnRelayClient.allocateRelay(
                    config.turnServer(), config.turnPort(),
                    config.turnRealm(), config.turnSharedSecret(),
                    config.agentName());
            if (turnRelayAddress != null) {
                metadata.put("turnRelayAddress", turnRelayAddress);
            }
        }

        // 4. Aimbase 등록 — CR-074: 실패해도 agent 자체는 계속 동작 (TURN-TCP 도달 경로 유지)
        List<String> toolNames = mcpServer.getToolNames();
        try {
            agentId = registrationClient.register(
                    config.agentName(), publicAddress, config.mcpPort(),
                    toolNames, metadata);
            log.info("Registered with Aimbase: agentId={}", agentId);
        } catch (Exception e) {
            log.warn("Aimbase registration failed — agent continues without registration: {}", e.getMessage());
            agentId = null;
        }

        // 5. 하트비트
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
     * CR-074: TURN-TCP allocate + ConnectionBind handler + loopback bridge 시작.
     */
    private void startTurnTcp(Map<String, Object> metadata) {
        if (config.turnServer() == null || config.turnServer().isBlank()) {
            log.warn("TURN-TCP enabled but turnServer is empty — skipping");
            return;
        }
        TurnTcpAllocator.Allocation alloc = TurnTcpAllocator.allocate(
                config.turnServer(), config.turnPort(),
                config.turnRealm(), config.turnSharedSecret(),
                config.agentName());
        if (alloc == null) {
            log.warn("TURN-TCP allocate failed — agent registers without runnerEndpoint");
            return;
        }
        this.turnRelayAddress = alloc.relayAddress();

        // BE 가 그대로 HTTP base URL 로 사용 가능
        String runnerEndpoint = "http://" + alloc.relayAddress();
        metadata.put("runnerEndpoint", runnerEndpoint);
        metadata.put("turnRelayAddress", alloc.relayAddress());
        log.info("Registered runnerEndpoint via TURN-TCP relay: {}", runnerEndpoint);

        // loopback bridge — 외부 socket → localhost:runnerPort
        this.loopbackBridge = new TurnLoopbackBridge(config.runnerPort());

        // ConnectionBind handler — control socket 위 ConnectionAttempt 처리
        this.bindHandler = new TurnConnectionBindHandler(
                config.turnServer(), config.turnPort(),
                alloc.controlSocket(), alloc.authMaterial(),
                loopbackBridge::bridge);
        bindHandler.start();

        // CR-074: RFC 5766 §9 — peer 화이트리스트 등록.
        // 이 IP 들에서 오는 incoming TCP 만 ConnectionAttempt 까지 진입 가능.
        if (config.turnAllowedPeerIps() != null && !config.turnAllowedPeerIps().isBlank()) {
            for (String peerIp : config.turnAllowedPeerIps().split(",")) {
                String ip = peerIp.trim();
                if (ip.isEmpty()) continue;
                TurnTcpAllocator.sendCreatePermission(alloc.controlSocket(), ip, alloc.authMaterial());
            }
        } else {
            log.warn("TURN-TCP: turnAllowedPeerIps is empty — incoming TCP from external clients will be rejected by TURN server");
        }

        // CR-074: RFC 5766 §7 — Allocate lifetime refresh.
        // lifetime 만료 전에 갱신. lifetime/2 주기로 보냄.
        int lifetime = alloc.lifetimeSeconds();
        long refreshIntervalSec = Math.max(60L, lifetime / 2L);
        this.turnRefreshScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "turn-refresh");
            t.setDaemon(true);
            return t;
        });
        turnRefreshScheduler.scheduleAtFixedRate(() -> {
            TurnTcpAllocator.sendRefresh(alloc.controlSocket(), lifetime, alloc.authMaterial());
            // Refresh 시 permission 도 함께 (TURN 권한도 만료될 수 있음)
            if (config.turnAllowedPeerIps() != null && !config.turnAllowedPeerIps().isBlank()) {
                for (String peerIp : config.turnAllowedPeerIps().split(",")) {
                    String ip = peerIp.trim();
                    if (ip.isEmpty()) continue;
                    TurnTcpAllocator.sendCreatePermission(alloc.controlSocket(), ip, alloc.authMaterial());
                }
            }
        }, refreshIntervalSec, refreshIntervalSec, TimeUnit.SECONDS);
        log.info("TURN-TCP refresh scheduler armed: every {}s", refreshIntervalSec);
    }

    /**
     * Agent 종료: 하트비트 중지 → Aimbase 해제 → MCP 서버 중지 → TURN 자원 정리.
     */
    @Override
    public void close() {
        log.info("Stopping agent '{}'...", config.agentName());

        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
            heartbeatScheduler = null;
        }

        if (agentId != null) {
            try { registrationClient.deregister(agentId); } catch (Exception ignore) {}
            agentId = null;
        }

        // TURN 자원 정리
        if (turnRefreshScheduler != null) {
            try { turnRefreshScheduler.shutdownNow(); } catch (Exception ignore) {}
            turnRefreshScheduler = null;
        }
        if (bindHandler != null) {
            try { bindHandler.close(); } catch (Exception ignore) {}
            bindHandler = null;
        }
        if (loopbackBridge != null) {
            try { loopbackBridge.shutdown(); } catch (Exception ignore) {}
            loopbackBridge = null;
        }

        mcpServer.stop();
        log.info("Agent '{}' stopped", config.agentName());
    }

    public String getAgentId() {
        return agentId;
    }

    public String getPublicAddress() {
        return publicAddress;
    }

    public String getTurnRelayAddress() {
        return turnRelayAddress;
    }

    public boolean isRunning() {
        return mcpServer.isRunning();
    }
}
