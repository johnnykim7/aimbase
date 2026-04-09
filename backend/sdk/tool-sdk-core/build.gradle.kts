plugins {
    `java-library`
}

dependencies {
    // Logging
    api("org.slf4j:slf4j-api:2.0.16")

    // Jackson (ZipExtractTool JSON serialization, ToolResult output)
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")

    // Test
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.assertj:assertj-core:3.27.3")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
