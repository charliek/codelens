plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
    application
}

application {
    mainClass.set("codelens.server.ApplicationKt")
}

dependencies {
    implementation(project(":server:core"))
    implementation(project(":server:classgraph"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.server.status.pages)

    implementation(libs.kotlinx.cli)
    implementation(libs.logback.classic)
}

tasks.shadowJar {
    archiveBaseName.set("codelens-server")
    archiveClassifier.set("all")
    archiveVersion.set("")
    mergeServiceFiles()
}
