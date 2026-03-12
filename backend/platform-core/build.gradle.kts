plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    // ── Spring Boot Core ──
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")

    // ── LLM SDKs ──
    implementation("com.anthropic:anthropic-java:2.12.0")
    implementation("com.openai:openai-java:2.1.0")

    // ── Spring AI ──
    implementation(platform("org.springframework.ai:spring-ai-bom:1.0.0"))
    implementation("org.springframework.ai:spring-ai-starter-model-openai")
    implementation("org.springframework.ai:spring-ai-starter-model-anthropic")
    implementation("org.springframework.ai:spring-ai-starter-vector-store-pgvector")

    // ── MCP Java SDK ──
    implementation(platform("io.modelcontextprotocol.sdk:mcp-bom:0.10.0"))
    implementation("io.modelcontextprotocol.sdk:mcp")
    implementation("io.modelcontextprotocol.sdk:mcp-spring-webmvc")

    // ── Database ──
    runtimeOnly("org.postgresql:postgresql")
    // hibernate-types-60 → hypersistence-utils-hibernate-63 (Hibernate 6.3+ / Spring Boot 3.4.x 호환)
    // 패키지명 com.vladmihalcea.* 동일 유지, 엔티티 코드 변경 없음
    implementation("io.hypersistence:hypersistence-utils-hibernate-63:3.9.0")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // ── Document Parsing (RAG - Phase 3) ──
    implementation("org.apache.tika:tika-core:3.1.0")
    implementation("org.apache.pdfbox:pdfbox:3.0.4")
    implementation("org.apache.poi:poi-ooxml:5.4.0")

    // ── JSON Schema Validation ──
    implementation("com.networknt:json-schema-validator:1.5.4")

    // ── Monitoring & Docs ──
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.4")

    // ── Test ──
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("com.redis:testcontainers-redis:2.2.2")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.4.2")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()

    // Docker 미실행 환경에서 TestContainers 기반 통합 테스트 자동 스킵
    val dockerAvailable = try {
        val process = ProcessBuilder("docker", "info").start()
        process.waitFor() == 0
    } catch (_: Exception) { false }

    if (!dockerAvailable) {
        exclude("**/integration/**")
        logger.lifecycle("⚠ Docker not available — skipping TestContainers integration tests")
    }

    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
        showExceptions = true
        showCauses = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
