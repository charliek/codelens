# Changelog

## Unreleased

Documentation:
- Migrated the docs site from Material for MkDocs to
  [Zensical](https://zensical.org), the successor from the same team (Material
  entered maintenance mode in November 2025 and now warns on every build that
  MkDocs 2.0 will remove the plugin and theming systems with no migration
  path). `mkdocs.yml` is replaced by a native `zensical.toml`; docs content is
  unchanged.

  The look now comes from the shared
  [stridelabs-docs-theme](https://github.com/charliek/stridelabs-docs-theme)
  package rather than per-repo config, so restyling the fleet is a version bump
  instead of an edit in every repo. Fonts are self-hosted by the theme — the
  site no longer requests anything from `fonts.googleapis.com` or
  `fonts.gstatic.com`.

  Verified against the pre-migration build: identical 12-page set and all 153
  heading anchors preserved across the 11 content pages, so existing deep links
  still resolve. Page `<title>` now derives from the page `<h1>` rather than the
  nav label, which is the one intentional difference.

- Added a `Docs PR Build` workflow. Docs previously built only on push to
  `main`, and without `--strict` — a broken link or anchor could land on `main`
  and was caught at deploy time or not at all. Both workflows now build
  `--strict` and watch `uv.lock`.

## v0.0.7

Distribution:
- Linux `.deb` packaging. codelens now publishes
  `codelens_<version>_<arch>.deb` for `amd64` and `arm64`, installable on
  Ubuntu/Pop!_OS 24.04+ via the apt-charliek repo (`sudo apt install codelens`)
  alongside the existing Homebrew path. The deb places the CLI at
  `/usr/local/bin/codelens` and the server JAR at
  `/usr/local/libexec/codelens-server-all.jar` — the libexec layout the CLI's
  JAR discovery already expects, matching Homebrew. No hard `Depends` on a JDK;
  the user supplies JDK 21+ (auto-discovered from `/usr/lib/jvm`, SDKMAN, mise,
  or Linuxbrew), as on macOS (#53).

Release process:
- Adopted the cc-plugins `release-workflows` release gate: a `ci-success`
  aggregate check (named exactly `ci-success` so the convention's
  `?check_name=ci-success` query matches) that `/release-workflows:release` and
  the release workflow poll before publishing, plus a `release-snapshot` CI job
  validating the deb artifacts on every PR. The release workflow now dispatches
  `charliek/apt-charliek` to republish apt metadata after each release (#53, #54).

## v0.0.6

Features:
- New `codelens-spring-web-analysis` skill (the Spring sibling of
  `codelens-ratpack-analysis`): endpoint inventory, request tracing,
  reactive-vs-blocking classification, `@Transactional` boundaries,
  security posture, exception handling, config binding, and DTO↔entity
  mapping — all composed from the existing framework-agnostic primitives.
  The `sample-spring-boot-app` fixture gained WebFlux routing,
  blocking-in-reactive handlers, `@RestControllerAdvice`,
  `@Transactional` self-invocation, Spring Security, MapStruct, and
  `@Valid`, with 10 new golden e2e cases (#46).
- Typed annotation attribute values. `AnnotationInfo.parameters` is now a
  typed `Map<String, AnnotationValue>` with an `AnnotationValueKind`
  discriminator (`STRING`/`BOOLEAN`/…/`CLASS`/`ENUM`/`ANNOTATION`/`ARRAY`)
  instead of a stringified map: arrays are real arrays, class literals
  carry the dotted FQN with no `.class`, enums carry `{value, enumType}`,
  and nested annotations recurse; sparse JSON omits absent fields. A route
  path is now `.parameters.value.items[0].value`, no bracket parsing
  (#41, #49). **Breaking wire-contract change** — all goldens regenerated.
- `calls --in-methods-returning <fqn>` / `--in-methods-annotated <fqn>`:
  keep only call-sites whose enclosing method returns a given type and/or
  carries a given (meta-expanded) annotation, ANDed when both are set and
  composable with `--method`. Adds `MethodInfo.descriptor` so overloads
  disambiguate. Makes blocking-in-reactive a one-query view (#44, #49).
- `annotations usages` is now scope-aware (`class`/`method`/`field`/`param`/`all`,
  default `all`) and returns the matched annotation's typed attribute
  values inline — every `@GetMapping` path, `@ExceptionHandler` type,
  `@PreAuthorize` expression, `@Value` key — as a unified, paginated,
  target-discriminated response (replaces the old class-only shape). Adds
  a shared overflow-safe pagination helper across the classes/methods/
  xref/annotations routes (#43).

Docs:
- Tag every bare code fence in `docs/reference/api.md` and `cli.md` with a
  language (markdownlint MD040) so the reference examples render with
  proper highlighting (#52).

Release process:
- CI: bump `actions/create-github-app-token` v2 → v3 (Node 24) and switch
  the release-bot token mint from the deprecated `app-id` to `client-id`
  (#48, #50).

## v0.0.5

Features:
- Cobra command groups in `codelens --help`: subcommands now render under
  three category headings (Server lifecycle, Code analysis, Kotlin tooling)
  instead of a flat list. `docs/reference/cli.md` updated to match (#40).

Release process:
- Adopt the `cc-plugins:release-workflows` convention. `scripts/release/update-version.sh`
  bumps both `version.txt` and `.claude-plugin/plugin.json` locally before the tag,
  with grep/jq-verify so silent set-version.sh no-ops fail loudly. `RELEASING.md`
  documents the per-repo policy + break-glass recovery. New
  `sanity-check-app.yml` verifies the release-bot App can reach the
  homebrew-tap before any release tries to push to it. (#47)
- Retire `HOMEBREW_TAP_TOKEN` PAT. GoReleaser's brews step now uses a
  release-bot App token minted at workflow time (scoped to
  `charliek/homebrew-tap` via `actions/create-github-app-token`'s
  `owner` + `repositories` inputs, with `permission-contents: write`
  defense-in-depth). Same App identity as roost, strix, and prox. The
  legacy secret has been deleted from the secret store.
- Server-side `Verify version files match tag` step in `release.yaml`
  catches any drift between the tagged commit's `version.txt` /
  `plugin.json` and the tag, before artifacts ship. Replaces the
  deleted `sync-version` job's contract.
- Branch protection ruleset on `main` now lists the release-bot App
  and admin role in `bypass_actors`.

## v0.0.4

Features:
- TTY-aware human/JSON dual output: commands render a human-readable table on a
  TTY and JSON when piped (or with `--json`); `--table` forces a table (#32).
- Warn when a project scans to 0 classes, signaling an uncompiled project so the
  fix (compile first) is obvious instead of an empty result (#36).

Fixes:
- Make the Gradle classpath init script compatible with Gradle's configuration
  cache (8.14+/9.x). It collected the classpath at task-execution time via
  `Task.project`, which the configuration cache forbids; resolution now reads all
  project state at configuration time so it no longer fails when the cache is
  enabled (#33).
- Broaden JDK resolution: handle SDKMAN vendor-alias forms (e.g. `21-tem`),
  discover JDKs under `/Library/Java/JavaVirtualMachines`, and give clearer
  errors when a project's declared JDK can't be resolved (#35).

Docs:
- Document installing the skills on the documentation site (#31).

## v0.0.3

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
- **`calls` now resolves lambda and method-reference targets.** Invocations made
  via `invokedynamic` (a `LambdaMetafactory` bootstrap) are captured as call sites
  flagged `invokeDynamic`, with `implMethodOwner`/`implMethodName` pointing at the
  implementation — a synthetic `lambda$…` body for a lambda, or the referenced
  method for a method reference — so inline handlers and lambda bodies are no longer
  invisible. `StringConcatFactory` (string concatenation) invokedynamics are
  recognized and skipped.
- **`xref` and `deps` now count generic type arguments.** Field, parameter, return,
  and supertype types are read from the generic signature, so a type that appears
  only as a type argument (`List<Foo>`, `Mono<Foo>`, `Repository<Foo, Id>`,
  `extends Base<Foo>`) is counted as a reference to that argument, not just its
  container, and `classes show` renders types in generic form.
- Claude Code skill triggering descriptions optimized, and the JVM- and
  Ratpack-analysis skills behavior-tested against the fixtures (the Ratpack
  inline-lambda route recipe was tightened to follow nested sub-chains).

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
