// A self-contained Micronaut + Flyway + R2DBC->JDBC library fixture (Kotlin, no
// web handlers) mirroring the analyzable structure of a real such project. It
// lets CodeLens prove the general primitives work on a *third* framework
// (Micronaut) with zero framework-specific tool code — entirely in-repo, with
// no dependency on anything outside the checkout.
//
// Versions are pinned for reproducibility. The annotation processor (kapt/ksp)
// is intentionally omitted: the fixture is only compiled and scanned, never run,
// so no generated bean definitions are needed — the annotations and type usages
// the analysis cares about are all present in the plain compiled bytecode.

plugins {
    kotlin("jvm") version "2.3.20"
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("io.micronaut:micronaut-context:4.10.17")
    implementation("jakarta.inject:jakarta.inject-api:2.0.1")
    implementation("org.flywaydb:flyway-core:11.9.2")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.slf4j:slf4j-api:2.0.12")
    implementation(kotlin("stdlib"))
}
