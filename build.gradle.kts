plugins {
    alias(libs.plugins.kotlin.serialization) apply false
    // Kover plugin classes are already on the classpath via buildSrc
    // (so subprojects can apply it through `codelens.kotlin-module`); we
    // apply it here without specifying a version so the root project can
    // aggregate coverage from all modules.
    id("org.jetbrains.kotlinx.kover")
}

val projectVersion = file("version.txt").readText().trim()

allprojects {
    group = "dev.codelens"
    version = projectVersion
}

// Aggregate coverage across all server modules.
dependencies {
    kover(project(":server:core"))
    kover(project(":server:classgraph"))
    kover(project(":server:ktlint"))
    kover(project(":server:gradle-resolver"))
    kover(project(":server:source-resolver"))
    kover(project(":server:app"))
}
