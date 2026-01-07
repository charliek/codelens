plugins {
    kotlin("jvm") version "2.0.21"
}

repositories {
    mavenCentral()
}

dependencies {
    // Kotlin
    implementation(kotlin("stdlib"))

    // Ratpack dependencies for testing detection
    implementation("io.ratpack:ratpack-core:1.9.0")
    implementation("io.ratpack:ratpack-guice:1.9.0")
    implementation("io.ratpack:ratpack-groovy:1.9.0")

    // Guice for DI testing
    implementation("com.google.inject:guice:5.1.0")
}
