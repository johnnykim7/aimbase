plugins {
    `java-library`
}

dependencies {
    api(project(":sdk:tool-sdk-core"))

    // MCP Java SDK
    implementation(platform("io.modelcontextprotocol.sdk:mcp-bom:0.10.0"))
    implementation("io.modelcontextprotocol.sdk:mcp")
    implementation("io.modelcontextprotocol.sdk:mcp-spring-webmvc")

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
