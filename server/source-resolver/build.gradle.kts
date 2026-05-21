plugins {
    id("codelens.kotlin-module")
}

dependencies {
    implementation(project(":server:core"))
    implementation(libs.logback.classic)

    // CFR decompiler for bytecode-to-source conversion.
    implementation(libs.cfr.decompiler)

    // HTTP client for Maven Central downloads.
    implementation(libs.okhttp)

    testImplementation(libs.okhttp.mockwebserver)
}
