pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "codelens"

include("server:core")
include("server:classgraph")
include("server:ktlint")
include("server:gradle-resolver")
include("server:source-resolver")
include("server:app")
