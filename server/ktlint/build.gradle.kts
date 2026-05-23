plugins {
    id("codelens.kotlin-module")
}

dependencies {
    implementation(project(":server:core"))
    implementation(libs.ktlint.rule.engine)
    implementation(libs.ktlint.ruleset.standard)
    implementation(libs.logback.classic)
}
