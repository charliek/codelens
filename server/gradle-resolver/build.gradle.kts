plugins {
    alias(libs.plugins.kotlin.jvm)
}

repositories {
    maven {
        url = uri("https://repo.gradle.org/gradle/libs-releases")
    }
}

dependencies {
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
