# Changelog

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
