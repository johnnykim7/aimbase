plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    // ── Aimbase Tool SDK ──
    implementation(project(":sdk:tool-sdk-core"))
    // CR-072: McpToolConversion 공유 (UnifiedToolDef → McpSchema.Tool)
    implementation(project(":sdk:tool-sdk-mcp"))

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
    // Anthropic은 직접 SDK(com.anthropic:anthropic-java) 사용 — Spring AI starter 불필요
    implementation("org.springframework.ai:spring-ai-starter-vector-store-pgvector")

    // ── MCP Java SDK ──
    implementation(platform("io.modelcontextprotocol.sdk:mcp-bom:2.0.0"))
    implementation("io.modelcontextprotocol.sdk:mcp")
    // 0.17.0: JSON 매퍼가 별도 모듈로 분리됨 (Jackson2 구현)
    implementation("io.modelcontextprotocol.sdk:mcp-json-jackson2")
    // 0.17.0 스키마 검증기는 networknt 2.0.0 의 Dialects 클래스 필요.
    // 버전은 아래 dependencyManagement.dependencies 에서 2.0.0 으로 강제(플러그인 공식 override 경로).
    implementation("com.networknt:json-schema-validator")

    // ── Database ──
    runtimeOnly("org.postgresql:postgresql")
    // hibernate-types-60 → hypersistence-utils-hibernate-63 (Hibernate 6.3+ / Spring Boot 3.4.x 호환)
    // 패키지명 com.vladmihalcea.* 동일 유지, 엔티티 코드 변경 없음
    implementation("io.hypersistence:hypersistence-utils-hibernate-63:3.9.0")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // ── Document Parsing (RAG - Phase 3) ──
    implementation("org.apache.tika:tika-core:3.1.0")
    // openhtmltopdf 1.1.113 이 pdfbox 3.0.8 을 끌어온다. 판이 몰래 올라가지 않게 같은 판을 적는다.
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    implementation("org.apache.poi:poi-ooxml:5.4.0")

    // ── 첨부 데이터 파일 계산 (CR-183): 호출마다 메모리 DB 를 열고 닫는다. 판은 Spring Boot BOM 관리 ──
    implementation("com.h2database:h2")

    // ── 채팅 문서 그리기 (CR-182): 마크다운 해석과 HTML 을 PDF 로 ──
    implementation("org.commonmark:commonmark:0.32.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.32.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.32.0")
    implementation("org.commonmark:commonmark-ext-autolink:0.32.0")
    implementation("io.github.openhtmltopdf:openhtmltopdf-pdfbox:1.1.113")

    // ── Skill Bundle zip (CR-147): 심볼릭 링크 항목을 가려내려면 zip 의 유닉스 모드가 필요하다 ──
    implementation("org.apache.commons:commons-compress:1.27.1")

    // ── JWT ──
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

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
    testImplementation("org.wiremock:wiremock-jetty12:3.9.2") // CR-054: HttpRequestTool unit tests (Jetty 12 server factory)
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.4.2")
    }
    // MCP SDK 0.17.0 스키마 검증기가 요구하는 networknt 2.0.0(Dialects 클래스) 강제.
    // io.spring.dependency-management 의 "selected by rule" 보다 이 명시 선언이 우선한다.
    dependencies {
        dependency("com.networknt:json-schema-validator:2.0.0")
    }
}

// CR-058 Sprint 53: 위젯 번들을 정적 리소스(/widget/v1/*)로 포함해 공개 서빙.
// packages/chat-widget-embed/dist/ 가 있으면 그 산출물로 폴더를 맞추고, 없으면 체크인된 사본을 그대로 쓴다.
// CR-177: 위젯이 조각 파일을 그때그때 불러오므로 진입 파일만 옮기면 화면이 뜨지 않는다. 조각 전부를 옮기고,
// 지난 빌드의 조각은 남지 않게 지운다. 지우는 것은 빌드가 만드는 맨 위 js 와 d.ts 뿐이고 안내 html, 예제 폴더는 남긴다.
val copyWidgetBundle by tasks.registering(Sync::class) {
    val widgetDist = rootProject.projectDir.resolve("../packages/chat-widget-embed/dist")
    onlyIf { widgetDist.exists() }
    from(widgetDist) {
        include("*.js", "index.d.ts")
        rename("^index\\.d\\.ts$", "aimbase-chat.d.ts")
    }
    into(layout.projectDirectory.dir("src/main/resources/static/widget/v1"))
    preserve {
        include("**/*")
        exclude("*.js", "aimbase-chat.d.ts")
    }
}

tasks.named("processResources") {
    dependsOn(copyWidgetBundle)
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
