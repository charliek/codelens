plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":server:core"))
    implementation(libs.ktlint.rule.engine)
    implementation(libs.ktlint.ruleset.standard)
    implementation(libs.logback.classic)

    // Test dependencies
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.mockk)
}

tasks.test {
    useJUnitPlatform()
}
