package com.platform.agent.lifecycle;

import com.platform.mcp.agent.AgentLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * CR-042: Spring 라이프사이클과 AgentLifecycle 연결.
 * 모든 Bean 준비 후 start, 종료 시 graceful close.
 */
@Component
public class AgentServiceManager implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AgentServiceManager.class);

    private final AgentLifecycle agentLifecycle;
    private volatile boolean running = false;

    public AgentServiceManager(AgentLifecycle agentLifecycle) {
        this.agentLifecycle = agentLifecycle;
    }

    @Override
    public void start() {
        log.info("Starting agent service...");
        agentLifecycle.start();
        running = true;
        log.info("Agent service started");
    }

    @Override
    public void stop() {
        log.info("Stopping agent service...");
        agentLifecycle.close();
        running = false;
        log.info("Agent service stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE; // Start last, stop first
    }
}
