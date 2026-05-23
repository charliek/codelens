plugins {
    id("codelens.kotlin-module")
}

dependencies {
    implementation(project(":server:core"))
    implementation(libs.gradle.tooling.api)
    implementation(libs.logback.classic)
}
