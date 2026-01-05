plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":server:core"))
    implementation(libs.classgraph)
}
