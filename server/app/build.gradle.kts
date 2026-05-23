plugins {
    id("codelens.kotlin-module")
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
    implementation(project(":server:ktlint"))
    implementation(project(":server:gradle-resolver"))
    implementation(project(":server:source-resolver"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.server.status.pages)

    implementation(libs.kotlinx.cli)
    implementation(libs.logback.classic)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
}

tasks.shadowJar {
    archiveFileName = "codelens-server-all.jar"
    mergeServiceFiles()
}
