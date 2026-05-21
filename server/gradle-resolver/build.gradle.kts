plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":server:core"))
    implementation(libs.gradle.tooling.api)
    implementation(libs.logback.classic)

    // Test dependencies
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.mockk)
}

tasks.test {
    useJUnitPlatform()
}
