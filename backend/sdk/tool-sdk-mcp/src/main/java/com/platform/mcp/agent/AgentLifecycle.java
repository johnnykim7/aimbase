package com.platform.mcp.agent;

import com.platform.tool.SdkToolKit;
import com.platform.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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

    // CR-079: TURN control socket 자동 재할당.
    // alloc 은 broken pipe 감지 시 새 Allocation 으로 교체된다 — refresh 람다가 항상 최신 socket 으로 동작하도록 mutable.
    private final AtomicReference<TurnTcpAllocator.Allocation> currentAllocation = new AtomicReference<>();
    // 연속 실패 카운터 — 단발 실패는 transient 일 수 있으므로 임계값 도달 시에만 재할당.
    private final AtomicInteger consecutiveRefreshFailures = new AtomicInteger(0);
    /** 연속 N회 refresh 실패 → broken 으로 간주하고 재할당 시도. */
    private static final int BROKEN_THRESHOLD = 2;
    /** 재할당 자체 실패 시 다음 시도까지 대기 (다음 refresh 주기에서 재시도). */
    private final AtomicInteger reallocateAttempts = new AtomicInteger(0);

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
                    config.agentName(), config.userId(), publicAddress, config.mcpPort(),
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
     * CR-079: control socket broken 감지 시 자동 재할당.
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
        this.currentAllocation.set(alloc);

        // BE 가 그대로 HTTP base URL 로 사용 가능
        String runnerEndpoint = "http://" + alloc.relayAddress();
        metadata.put("runnerEndpoint", runnerEndpoint);
        metadata.put("turnRelayAddress", alloc.relayAddress());
        log.info("Registered runnerEndpoint via TURN-TCP relay: {}", runnerEndpoint);

        // loopback bridge — 외부 socket → localhost:runnerPort
        this.loopbackBridge = new TurnLoopbackBridge(config.runnerPort());

        // ConnectionBind handler — control socket 위 ConnectionAttempt 처리
        startBindHandler(alloc);

        // CR-074: RFC 5766 §9 — peer 화이트리스트 등록.
        sendAllPermissions(alloc);

        // CR-074: RFC 5766 §7 — Allocate lifetime refresh.
        // CR-079 보강 +1: nonce 가 stale 이 되어 ConnectionBind 가 438 로 거부되는 케이스 대응.
        //   coturn 의 nonce TTL 보다 짧게(=60s) 자주 갱신해 ConnectionBind 가 stale 만날 확률을 낮춘다.
        // CR-076: 매 호출 시 authSession.current() 로 최신 nonce 사용.
        // CR-079: 연속 실패 시 control socket 자체를 재할당.
        int lifetime = alloc.lifetimeSeconds();
        long refreshIntervalSec = 60L;
        this.turnRefreshScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "turn-refresh");
            t.setDaemon(true);
            return t;
        });
        turnRefreshScheduler.scheduleAtFixedRate(() -> safelyRunRefreshTick(lifetime),
                refreshIntervalSec, refreshIntervalSec, TimeUnit.SECONDS);
        log.info("TURN-TCP refresh scheduler armed: every {}s", refreshIntervalSec);
    }

    /**
     * CR-079: refresh 스케줄러의 단일 tick.
     * <ol>
     *   <li>현재 allocation 으로 Refresh + CreatePermission 송신</li>
     *   <li>control socket 이 close 됐거나 송신이 연속 실패하면 broken 으로 간주</li>
     *   <li>broken 시 재할당 시도 → 성공 시 metadata 갱신 + Aimbase 재등록</li>
     * </ol>
     * 람다가 아닌 별도 메서드로 두어 unchecked 예외가 스케줄러를 침묵시키지 않도록 try-catch 로 감싼다.
     */
    private void safelyRunRefreshTick(int lifetime) {
        try {
            TurnTcpAllocator.Allocation alloc = currentAllocation.get();
            if (alloc == null) {
                // 이전 재할당이 실패한 상태 — 이번 tick 에서 다시 시도.
                attemptReallocate();
                return;
            }

            // CR-079 보강: Java Socket.isClosed() 는 로컬 close() 후에만 true — 외부 RST 는 못 잡음.
            // 그래서 receiveLoop 가 RST/EOF 를 잡았을 때 set 하는 controlBroken 플래그를 같이 본다.
            boolean socketAlive = alloc.controlSocket() != null
                    && !alloc.controlSocket().isClosed()
                    && alloc.controlSocket().isConnected();
            boolean broken = bindHandler != null && bindHandler.isControlBroken();
            if (!socketAlive || broken) {
                log.warn("TURN control socket detected broken (socketAlive={}, controlBroken={}) — triggering reallocate",
                        socketAlive, broken);
                consecutiveRefreshFailures.set(BROKEN_THRESHOLD);
                attemptReallocate();
                return;
            }

            boolean refreshOk = TurnTcpAllocator.sendRefresh(
                    alloc.controlSocket(), lifetime, alloc.authSession());

            if (refreshOk) {
                // Refresh 시 permission 도 함께 (TURN 권한도 만료될 수 있음)
                sendAllPermissions(alloc);
                if (consecutiveRefreshFailures.getAndSet(0) > 0) {
                    log.info("TURN refresh recovered after transient failures");
                }
                return;
            }

            int failures = consecutiveRefreshFailures.incrementAndGet();
            log.warn("TURN refresh failed (consecutive={}, threshold={})",
                    failures, BROKEN_THRESHOLD);
            if (failures >= BROKEN_THRESHOLD) {
                attemptReallocate();
            }
        } catch (Throwable t) {
            // ScheduledExecutorService 는 unchecked 예외 1회 던지면 task 가 영원히 정지한다.
            // tick 전체를 swallow 해서 다음 주기에 재시도하도록 보장.
            log.warn("TURN refresh tick failed unexpectedly: {}", t.getMessage(), t);
        }
    }

    /**
     * CR-079: 새 Allocation 획득 → bindHandler/loopbackBridge 갱신 → BE 재등록.
     * 실패 시 다음 tick 에서 재시도 (currentAllocation 은 갱신하지 않음).
     */
    private synchronized void attemptReallocate() {
        int attempt = reallocateAttempts.incrementAndGet();
        log.warn("TURN-TCP reallocate attempt #{} (turnServer={}:{})",
                attempt, config.turnServer(), config.turnPort());

        TurnTcpAllocator.Allocation old = currentAllocation.get();

        // 새 Allocation 시도 — 실패해도 기존 자원은 건드리지 않음.
        TurnTcpAllocator.Allocation fresh = TurnTcpAllocator.allocate(
                config.turnServer(), config.turnPort(),
                config.turnRealm(), config.turnSharedSecret(),
                config.agentName());
        if (fresh == null) {
            log.warn("TURN-TCP reallocate #{} failed — will retry next refresh tick", attempt);
            return;
        }

        // 기존 bindHandler 와 control socket 정리.
        TurnConnectionBindHandler oldHandler = this.bindHandler;
        if (oldHandler != null) {
            try { oldHandler.close(); } catch (Exception ignore) {}
        }
        if (old != null && old.controlSocket() != null) {
            try { old.controlSocket().close(); } catch (IOException ignore) {}
        }

        // 새 자원 설치.
        this.currentAllocation.set(fresh);
        this.turnRelayAddress = fresh.relayAddress();
        startBindHandler(fresh);
        sendAllPermissions(fresh);
        consecutiveRefreshFailures.set(0);

        log.info("TURN-TCP reallocate #{} success: new relay={}", attempt, fresh.relayAddress());

        // BE 재등록 — register() 는 publicAddress+mcpPort 매칭 또는 userId 기반 덮어쓰기 정책.
        // 따라서 같은 publicAddress/mcpPort 로 재호출하면 BE entity 가 새 metadata.runnerEndpoint 로 갱신된다.
        reregisterAfterReallocate(fresh);
    }

    /**
     * CR-079: 재할당 후 새 runnerEndpoint 로 Aimbase 재등록.
     * 실패해도 agent 동작 자체는 계속 — 다음 호출 또는 heartbeat 에서 BE 가 stale 를 인식해 회복한다.
     */
    private void reregisterAfterReallocate(TurnTcpAllocator.Allocation fresh) {
        try {
            String runnerEndpoint = "http://" + fresh.relayAddress();
            Map<String, Object> metadata = new HashMap<>(Map.of("sdk_version", "1.0.0"));
            metadata.put("runnerEndpoint", runnerEndpoint);
            metadata.put("turnRelayAddress", fresh.relayAddress());

            List<String> toolNames = mcpServer.getToolNames();
            String newAgentId = registrationClient.register(
                    config.agentName(), config.userId(), publicAddress, config.mcpPort(),
                    toolNames, metadata);
            // BE 정책상 publicAddress+mcpPort 가 같으면 같은 agentId 가 반환되거나,
            // userId 가 같으면 이전 ACTIVE 가 DEREGISTERED 되고 새 id 가 발급될 수 있음.
            this.agentId = newAgentId;
            log.info("Aimbase re-registered with new runnerEndpoint: agentId={}, endpoint={}",
                    newAgentId, runnerEndpoint);
        } catch (Exception e) {
            log.warn("Aimbase re-registration after reallocate failed — agent continues: {}",
                    e.getMessage());
        }
    }

    /**
     * CR-079: ConnectionBind handler 시작 — 재할당 후에도 동일 절차이므로 분리.
     * 보강: handler 가 control socket RST/EOF 감지하면 즉시 reallocate 콜백 트리거 (refresh 주기 대기 없음).
     */
    private void startBindHandler(TurnTcpAllocator.Allocation alloc) {
        // CR-076: authSession 은 mutable holder — receiveLoop 가 438 응답에서 새 nonce 추출 시 갱신.
        TurnConnectionBindHandler handler = new TurnConnectionBindHandler(
                config.turnServer(), config.turnPort(),
                alloc.controlSocket(), alloc.authSession(),
                loopbackBridge::bridge);
        handler.setOnControlBroken(this::onControlBrokenAsync);
        handler.start();
        this.bindHandler = handler;
    }

    /**
     * CR-079 보강: bindHandler 가 broken 감지하면 호출. 별도 스레드에서 reallocate 트리거 (콜백 스레드 차단 회피).
     */
    private void onControlBrokenAsync() {
        log.warn("TURN control broken signal received — scheduling immediate reallocate");
        Thread t = new Thread(() -> {
            try { attemptReallocate(); }
            catch (Throwable th) { log.warn("immediate reallocate failed: {}", th.getMessage()); }
        }, "turn-immediate-reallocate");
        t.setDaemon(true);
        t.start();
    }

    /**
     * CR-079: 화이트리스트 IP 들에 CreatePermission 송신 — 재할당 후에도 동일.
     */
    private void sendAllPermissions(TurnTcpAllocator.Allocation alloc) {
        if (config.turnAllowedPeerIps() == null || config.turnAllowedPeerIps().isBlank()) {
            log.warn("TURN-TCP: turnAllowedPeerIps is empty — incoming TCP from external clients will be rejected by TURN server");
            return;
        }
        for (String peerIp : config.turnAllowedPeerIps().split(",")) {
            String ip = peerIp.trim();
            if (ip.isEmpty()) continue;
            TurnTcpAllocator.sendCreatePermission(
                    alloc.controlSocket(), ip, alloc.authSession());
        }
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
        // CR-079: bindHandler.close() 가 control socket 도 같이 닫지만, 재할당 race 로 다른 alloc 이 보유 중일 수 있음.
        TurnTcpAllocator.Allocation alloc = currentAllocation.getAndSet(null);
        if (alloc != null && alloc.controlSocket() != null && !alloc.controlSocket().isClosed()) {
            try { alloc.controlSocket().close(); } catch (IOException ignore) {}
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
