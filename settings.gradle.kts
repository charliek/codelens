pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "codelens"

include("server:core")
include("server:classgraph")
include("server:app")
