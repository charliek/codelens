plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

val projectVersion = file("version.txt").readText().trim()

allprojects {
    group = "dev.codelens"
    version = projectVersion

    repositories {
        mavenCentral()
        // Required for Gradle Tooling API
        maven {
            url = uri("https://repo.gradle.org/gradle/libs-releases")
        }
    }
}

subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            jvmTarget = "21"
            freeCompilerArgs = listOf("-Xjsr305=strict")
        }
    }
}
