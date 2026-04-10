package com.platform.agent.lifecycle;

import com.platform.mcp.agent.AgentLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * CR-042: 5분 주기 상태 로그 + status.json 기록.
 */
@Component
public class AgentHealthReporter {

    private static final Logger log = LoggerFactory.getLogger(AgentHealthReporter.class);
    private static final Path STATUS_FILE = Path.of(
            System.getProperty("user.home"), ".aimbase-agent", "status.json");

    private final AgentLifecycle lifecycle;

    public AgentHealthReporter(AgentLifecycle lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Scheduled(fixedRate = 300_000) // 5 minutes
    public void reportHealth() {
        boolean running = lifecycle.isRunning();
        String agentId = lifecycle.getAgentId();
        String publicAddress = lifecycle.getPublicAddress();

        log.info("Health: running={}, agentId={}, publicAddress={}", running, agentId, publicAddress);

        writeStatusFile(running, agentId, publicAddress);
    }

    private void writeStatusFile(boolean running, String agentId, String publicAddress) {
        try {
            Files.createDirectories(STATUS_FILE.getParent());
            String json = """
                    {
                      "running": %s,
                      "agentId": "%s",
                      "publicAddress": "%s",
                      "timestamp": "%s"
                    }""".formatted(
                    running,
                    agentId != null ? agentId : "",
                    publicAddress != null ? publicAddress : "",
                    Instant.now().toString()
            );
            Files.writeString(STATUS_FILE, json);
        } catch (IOException e) {
            log.warn("Failed to write status file: {}", e.getMessage());
        }
    }
}
