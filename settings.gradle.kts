pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven {
            // Required for Gradle Tooling API.
            url = uri("https://repo.gradle.org/gradle/libs-releases")
        }
    }
}

rootProject.name = "codelens"

include("server:core")
include("server:classgraph")
include("server:ktlint")
include("server:gradle-resolver")
include("server:source-resolver")
include("server:app")
