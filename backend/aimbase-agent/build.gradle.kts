plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":sdk:tool-sdk-mcp"))

    // Spring Boot starter (config binding, lifecycle, scheduling)
    implementation("org.springframework.boot:spring-boot-starter")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
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
