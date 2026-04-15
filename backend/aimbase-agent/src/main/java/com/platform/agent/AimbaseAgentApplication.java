package com.platform.agent;

import com.platform.mcp.agent.AgentMcpServer;
import com.platform.tool.SdkToolKit;
import com.platform.tool.ToolExecutor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Arrays;
import java.util.List;

/**
 * CR-042: Aimbase Agent 독립 실행형 모듈.
 * SDK 도구를 MCP 프로토콜로 노출하는 경량 서비스.
 * DB/Redis 불필요 — Aimbase 서버에 등록 후 원격 도구 제공.
 *
 * CR-044 PRD-282: --mcp-stdio 플래그로 stdio MCP 서버 모드 진입 가능.
 * Claude CLI가 --mcp-config로 이 jar를 자식 프로세스로 기동할 때 사용.
 * stdio 모드에서는 Aimbase 서버 연결 불필요 — 도구 실행만 담당.
 */
@SpringBootApplication(
        scanBasePackages = "com.platform.agent",
        exclude = {
                DataSourceAutoConfiguration.class,
                HibernateJpaAutoConfiguration.class
        }
)
@EnableScheduling
public class AimbaseAgentApplication {

    public static void main(String[] args) {
        // CR-044: stdio MCP 모드 — Aimbase 서버 등록 없이 도구만 제공
        if (Arrays.asList(args).contains("--mcp-stdio")) {
            runStdioMode();
            return;
        }
        SpringApplication app = new SpringApplication(AimbaseAgentApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.run(args);
    }

    /**
     * stdio MCP 모드: Spring Boot 없이 SdkToolKit 도구만 MCP로 노출.
     * Claude CLI의 자식 프로세스로 기동되며, stdin/stdout이 MCP 채널이 된다.
     * Aimbase URL/API Key 불필요.
     */
    private static void runStdioMode() {
        String workspace = System.getProperty("user.home") + "/aimbase-workspace";
        SdkToolKit kit = new SdkToolKit(workspace);
        List<ToolExecutor> tools = kit.getAllTools();
        AgentMcpServer server = new AgentMcpServer(tools, 0);
        server.startStdio(); // 블로킹 — stdin이 닫힐 때까지 실행
    }
}
