package com.platform.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * CR-042: Aimbase Agent 독립 실행형 모듈.
 * SDK 도구를 MCP 프로토콜로 노출하는 경량 서비스.
 * DB/Redis 불필요 — Aimbase 서버에 등록 후 원격 도구 제공.
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
        SpringApplication app = new SpringApplication(AimbaseAgentApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.run(args);
    }
}
