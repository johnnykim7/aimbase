package com.platform.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI 3.0 (Swagger) 문서 설정.
 *
 * Swagger UI: http://localhost:8080/swagger-ui.html
 * API JSON:   http://localhost:8080/api-docs
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI platformOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Aimbase API")
                        .version("v1.0.0")
                        .description("""
                                Aimbase — Enterprise AI Management Platform API.

                                **주요 기능:**
                                - Multi-LLM 연동 (Anthropic Claude, OpenAI GPT, Ollama)
                                - MCP (Model Context Protocol) 도구 통합
                                - RAG (Retrieval-Augmented Generation) 지식 검색
                                - 워크플로우 DAG 실행 엔진
                                - Policy & Safety (PII 마스킹, Rate Limiting, 승인 플로우)
                                - Extension 시스템 (커스텀 도구 번들)
                                - Database-per-Tenant 멀티테넌시

                                **인증:** X-Tenant-Id 헤더로 테넌트를 식별합니다.
                                """)
                        .contact(new Contact()
                                .name("Platform Team")
                                .email("platform@example.io"))
                        .license(new License()
                                .name("Private")
                                .url("https://example.io")))
                .servers(List.of(
                        new Server()
                                .url("http://localhost:8080")
                                .description("Local Development"),
                        new Server()
                                .url("https://api.platform.io")
                                .description("Production")))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("JWT 토큰 인증. Authorization: Bearer {token}")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
