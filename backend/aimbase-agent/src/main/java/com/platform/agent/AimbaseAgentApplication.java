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
 *
 * CR-071 Phase 2: --runner-mode 플래그로 ClaudeCliRunner HTTP 서비스 진입.
 * Aimbase 서버의 ClaudeCliAdapter 가 HTTP(/v1/chat 등) 로 호출. 같은 PC 또는 사용자 PC 어디든 배치 가능.
 */
@SpringBootApplication(
        scanBasePackages = {
                "com.platform.agent.config",
                "com.platform.agent.lifecycle",
                "com.platform.agent.runner"   // CR-071 Phase 2: --runner-mode 컴포넌트
        },
        exclude = {
                DataSourceAutoConfiguration.class,
                HibernateJpaAutoConfiguration.class,
                // CR-071: platform-core 가 클래스패스에 들어와 Spring AI / Redis / RabbitMQ 등이
                // 자동설정을 시도하지만, aimbase-agent 는 이 빈들을 사용하지 않는다.
                org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration.class,
                org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration.class,
                org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration.class
        },
        // Spring AI / Spring Security 등 platform-core 가 전이로 끌어오는 starter 의 자동설정도 차단
        excludeName = {
                "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration",
                "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration",
                "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration",
                "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration",
                "org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration",
                "org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration",
                "org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration",
                "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration",
                "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration",
                "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration",
                "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
                "org.springframework.boot.autoconfigure.batch.BatchAutoConfiguration"
        }
)
@EnableScheduling
public class AimbaseAgentApplication {

    public static void main(String[] args) {
        List<String> argList = Arrays.asList(args);
        // CR-044: stdio MCP 모드 — Aimbase 서버 등록 없이 도구만 제공
        if (argList.contains("--mcp-stdio")) {
            runStdioMode();
            return;
        }

        SpringApplication app = new SpringApplication(AimbaseAgentApplication.class);

        // CR-071 Phase 2: --runner-mode 진입 시 HTTP 서버 + RunnerController 활성화
        if (argList.contains("--runner-mode")) {
            System.setProperty("aimbase.runner.enabled", "true");
            // 등록 모드(서버 push) 비활성 — Runner 단독 운용 시 Aimbase 서버 등록은 별도 시동 필요
            app.setWebApplicationType(WebApplicationType.SERVLET);
        } else {
            app.setWebApplicationType(WebApplicationType.NONE);
        }
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
