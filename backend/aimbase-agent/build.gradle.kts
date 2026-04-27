plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":sdk:tool-sdk-mcp"))

    // CR-071 Phase 2: ClaudeCliRunner — cli-runner 모듈에서 Worker/Pool/CommandBuilder 재사용
    implementation(project(":cli-runner"))

    // Spring Boot starter (config binding, lifecycle, scheduling)
    implementation("org.springframework.boot:spring-boot-starter")
    // CR-071 Phase 2: --runner-mode 진입 시 HTTP 서버 (RunnerController)
    implementation("org.springframework.boot:spring-boot-starter-web")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // CR-071 Phase 2: RunnerController 단위 테스트
    testImplementation("org.assertj:assertj-core:3.27.3")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito:mockito-junit-jupiter:5.14.2")
}

springBoot {
    mainClass.set("com.platform.agent.AimbaseAgentApplication")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// ── jpackage tasks ──────────────────────────────────────────
val jpackageDir = layout.buildDirectory.dir("jpackage")

tasks.register<Exec>("jpackageMac") {
    dependsOn(tasks.named("bootJar"))
    group = "distribution"
    description = "Build macOS DMG installer via jpackage"

    val jarFile = tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar")
        .get().archiveFile.get().asFile

    commandLine(
        "${System.getProperty("java.home")}/bin/jpackage",
        "--type", "dmg",
        "--name", "AimbaseAgent",
        "--app-version", "1.0.0",
        "--vendor", "Platform Inc.",
        "--description", "Aimbase Tool Agent",
        "--input", jarFile.parentFile.absolutePath,
        "--main-jar", jarFile.name,
        "--main-class", "org.springframework.boot.loader.launch.JarLauncher",
        "--java-options", "-Xmx512m",
        "--java-options", "-Dspring.config.additional-location=file:\${user.home}/.aimbase-agent/config/",
        "--dest", jpackageDir.get().asFile.absolutePath,
        "--mac-package-name", "AimbaseAgent"
    )
}

tasks.register<Exec>("jpackageWin") {
    dependsOn(tasks.named("bootJar"))
    group = "distribution"
    description = "Build Windows MSI installer via jpackage"

    val jarFile = tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar")
        .get().archiveFile.get().asFile

    commandLine(
        "${System.getProperty("java.home")}/bin/jpackage",
        "--type", "msi",
        "--name", "AimbaseAgent",
        "--app-version", "1.0.0",
        "--vendor", "Platform Inc.",
        "--description", "Aimbase Tool Agent",
        "--input", jarFile.parentFile.absolutePath,
        "--main-jar", jarFile.name,
        "--main-class", "org.springframework.boot.loader.launch.JarLauncher",
        "--java-options", "-Xmx512m",
        "--java-options", "-Dspring.config.additional-location=file:\${user.home}/.aimbase-agent/config/",
        "--dest", jpackageDir.get().asFile.absolutePath,
        "--win-dir-chooser",
        "--win-menu",
        "--win-shortcut",
        "--win-per-user-install"
    )
}
