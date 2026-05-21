plugins {
    id("codelens.kotlin-module")
}

dependencies {
    implementation(project(":server:core"))
    implementation(libs.classgraph)
    implementation(libs.asm)
    implementation(libs.logback.classic)
}
