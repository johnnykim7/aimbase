plugins {
    `java-library`
}

dependencies {
    // ClaudeCliWorker 가 platform-core 의 LLMRequest/Response/UnifiedMessage 등 사용 중
    // Phase 4 에서 어댑터가 cli-runner 를 의존하는 방향으로 정리될 예정 (현재는 양방향 의존 회피 위해 platform-core 의존 유지)
    api(project(":platform-core"))
    api("org.slf4j:slf4j-api:2.0.16")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito:mockito-junit-jupiter:5.14.2")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.4.2")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
