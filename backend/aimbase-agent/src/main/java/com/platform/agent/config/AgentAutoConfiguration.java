package com.platform.agent.config;

import com.platform.mcp.agent.AgentConfig;
import com.platform.mcp.agent.AgentLifecycle;
import com.platform.tool.SdkToolKit;
import com.platform.tool.ToolExecutor;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * CR-042: Agent Bean 조립.
 * SdkToolKit → ToolFilterService → AgentConfig → AgentLifecycle 순서로 구성.
 */
@Configuration
@EnableConfigurationProperties(AgentProperties.class)
public class AgentAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgentAutoConfiguration.class);

    private final AgentProperties props;

    public AgentAutoConfiguration(AgentProperties props) {
        this.props = props;
    }

    @PostConstruct
    void validate() {
        if (props.getAimbaseUrl() == null || props.getAimbaseUrl().isBlank()) {
            throw new IllegalStateException(
                    "agent.aimbase-url is required. Edit ~/.aimbase-agent/config/application.yml");
        }
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            throw new IllegalStateException(
                    "agent.api-key is required. Edit ~/.aimbase-agent/config/application.yml");
        }
    }

    @Bean
    public SdkToolKit sdkToolKit() {
        String workspace = resolveWorkspace();
        return new SdkToolKit(workspace);
    }

    @Bean
    public ToolFilterService toolFilterService() {
        return new ToolFilterService(props.getDisabledTools());
    }

    @Bean
    public AgentConfig agentConfig() {
        String workspace = resolveWorkspace();
        return new AgentConfig(
                props.getName(),
                props.getAimbaseUrl(),
                props.getApiKey(),
                props.getMcpPort(),
                workspace,
                props.getHeartbeatIntervalMs(),
                props.getStunServer(),
                props.getStunPort()
        );
    }

    @Bean
    public AgentLifecycle agentLifecycle(AgentConfig config, SdkToolKit kit,
                                         ToolFilterService filterService) {
        List<ToolExecutor> tools = filterService.filter(kit.getAllTools());
        return new AgentLifecycle(config, tools);
    }

    private String resolveWorkspace() {
        String path = props.getWorkspacePath();
        if (path == null || path.isBlank()) {
            path = Path.of(System.getProperty("user.home"), "aimbase-workspace").toString();
        }
        // Ensure workspace directory exists
        Path workspaceDir = Path.of(path);
        if (!Files.exists(workspaceDir)) {
            try {
                Files.createDirectories(workspaceDir);
                log.info("Created workspace directory: {}", path);
            } catch (IOException e) {
                log.warn("Failed to create workspace directory: {}", path, e);
            }
        }
        return path;
    }
}
