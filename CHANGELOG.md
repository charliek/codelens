# Changelog

## v0.0.2

- The target project's JDK must now be **declared**; codelens resolves it and
  passes it to the project's Gradle daemon, decoupled from the (possibly newer)
  server JVM. A missing or unresolvable declaration is a clear error instead of a
  cryptic Gradle failure. Fixes classpath resolution breaking when a newer JDK
  (e.g. 25) is installed and the target uses an older Gradle (e.g. 8.6).
- Project JDK declaration sources now include **mise** (`.mise.toml`,
  `mise.toml`, `.tool-versions`) alongside `.sdkmanrc`, `.java-version`, and
  `gradle.properties`; versions resolve via SDKMAN → Homebrew → mise. mise is
  also consulted when selecting the server JVM.
- `--project-java` remains the explicit override.

## v0.0.1

Initial public release.

- JVM codebase analysis (Java & Kotlin) via a Go CLI and a background
  Kotlin/Ktor server: classes, methods, annotations, type hierarchies,
  dependencies, and source (project, library, and JDK).
- Distributed via Homebrew (`brew tap charliek/tap && brew install codelens`),
  with the server JAR bundled alongside the binary.
- Server JDK auto-discovery across SDKMAN and Homebrew (newest installed JDK in
  21–25); target-project JDK auto-discovery for older-Gradle projects.
- Ratpack-migration helpers (handlers, promises, complexity, routes) as a
  secondary capability.
- Documentation site at https://charliek.github.io/codelens/.
