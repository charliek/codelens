// A deliberately rich Spring Boot fixture for exercising CodeLens's general JVM
// analysis (classes, methods, calls, xref, deps) against a third framework.
//
// Versions are pinned via the Spring Boot BOM (imported with Gradle's native
// platform(), so no Spring Boot Gradle plugin is needed) which keeps the
// resolved classpath deterministic for reproducible golden fixtures. The app is
// never run — the e2e harness only compiles it (`gradlew classes`) so CodeLens
// has bytecode to scan.

plugins {
    java
    kotlin("jvm") version "2.3.20"
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.3.5"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    runtimeOnly("com.h2database:h2")

    implementation(kotlin("stdlib"))
}
