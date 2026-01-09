plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":server:core"))
    implementation(libs.logback.classic)

    // CFR decompiler for bytecode-to-source conversion
    implementation(libs.cfr.decompiler)

    // HTTP client for Maven Central downloads
    implementation(libs.okhttp)

    // Test dependencies
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.mockk)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    useJUnitPlatform()
}
