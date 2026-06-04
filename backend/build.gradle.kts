plugins {
    id("org.springframework.boot") version "3.4.2" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")

    group = "com.platform"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }

    // MCP SDK 0.17.0 스키마 검증기는 networknt 2.0.0 의 Dialects 클래스를 요구한다.
    // io.spring.dependency-management 가 transitive 1.5.4 를 "selected by rule" 로 고정하므로,
    // 모든 서브프로젝트에서 충돌해결 규칙 자체를 2.0.0 으로 강제한다.
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "com.networknt" && requested.name == "json-schema-validator") {
                useVersion("2.0.0")
                because("MCP SDK 0.17.0 requires networknt 2.0.0 (Dialects class)")
            }
        }
    }

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }
}
