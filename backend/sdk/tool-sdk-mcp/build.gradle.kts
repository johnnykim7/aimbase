plugins {
    `java-library`
}

dependencies {
    api(project(":sdk:tool-sdk-core"))

    // MCP Java SDK
    implementation(platform("io.modelcontextprotocol.sdk:mcp-bom:0.17.0"))
    implementation("io.modelcontextprotocol.sdk:mcp")
    implementation("io.modelcontextprotocol.sdk:mcp-spring-webmvc")
    // 0.17.0: JSON 매퍼가 별도 모듈로 분리됨 (Jackson2 구현)
    implementation("io.modelcontextprotocol.sdk:mcp-json-jackson2")
    // 0.17.0 스키마 검증기는 networknt 2.0.0 의 Dialects 클래스 필요 (1.5.x 다운그레이드 방지).
    implementation("com.networknt:json-schema-validator") {
        version { strictly("2.0.0") }
    }

    // Spring Web (MCP SSE transport)
    implementation("org.springframework.boot:spring-boot-starter-web:3.4.2")

    // Jackson
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")

    // Test
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.assertj:assertj-core:3.27.3")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
