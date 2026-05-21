plugins {
    alias(libs.plugins.kotlin.serialization) apply false
}

val projectVersion = file("version.txt").readText().trim()

allprojects {
    group = "dev.codelens"
    version = projectVersion
}
