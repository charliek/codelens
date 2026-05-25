# Changelog

## Unreleased

- **Generalized from a Ratpack-specific tool to a general JVM facts engine.**
  The Ratpack-specific server endpoints, detectors, models, and CLI command
  groups (`handlers`, `promises`, `migration`, `modules`, `integrations`,
  `antipatterns`, `routes`) have been removed and replaced by framework-agnostic
  primitives:
  - **`calls <fqn> [--method]`** — forward call-site extraction: the invocations
    a method makes, with constant arguments and line numbers, from bytecode.
  - **`xref <typeFqn>`** — inverse type cross-reference: everything that
    references a type (extends/implements/field/param/return/annotation/
    instantiation/call-receiver), with server-side narrowing and aggregates.
  - **`deps`** — the project-wide dependency graph (JSON/DOT) and `deps
    foundation` (most depended-on classes), promoted out of the old
    `/api/v1/ratpack/dependencies*` namespace.
- **Breaking wire-contract change:** all `/api/v1/ratpack/*` endpoints are gone;
  the dependency-graph endpoints moved to `/api/v1/graph` and
  `/api/v1/graph/foundation`. `ProjectInfo.handlerCount` was removed.
- Framework-specific analysis (e.g. a Ratpack migration assessment) now lives in
  Claude Code skills as recipes over the general primitives, not in the binary.
- Test fixtures expanded to three frameworks — `sample-ratpack-app`,
  `sample-spring-boot-app`, and `sample-micronaut-app` — each scanned by the
  golden e2e suite to prove the primitives generalize.

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
