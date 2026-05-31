// Package e2e runs the CLI against a live server and asserts that its
// --json output matches committed golden fixtures after normalizing mutable
// fields (timestamps, uptimes, pid, port) and templating machine-specific
// absolute paths.
//
// This is the executable regression contract for the CLI's rendered output.
// Adding a new endpoint to the CLI should usually mean adding a Case here and
// regenerating the goldens with UPDATE_GOLDEN=1.
package e2e

// Case is one (command, args) tuple to exercise against the server.
type Case struct {
	Name string
	// Args are passed AFTER the global --project flag (which the runner
	// injects automatically) and BEFORE --json (also injected).
	Args []string
	// BlankPaths is a list of jq-style paths to blank out before
	// comparison. Use this only for genuinely mutable fields (timestamps,
	// pid, port, uptime). Server-deterministic data should not need
	// normalization.
	//
	// Examples:
	//   "uptime"                 — top-level key on /admin/info
	//   "appliedFilter.source"   — nested
	//   "classes.*.scannedAt"    — every element of an array
	BlankPaths []string
	// ExpectExitCode is the exit code the CLI is expected to return. 0
	// (the zero value) means success. Set to 1 to lock the contract that a
	// command exits non-zero — e.g. lint check on a project with violations.
	ExpectExitCode int
	// SkipUntilReady runs the case after the server has been warmed up
	// (already used by every case; kept here for future variations).
	SkipUntilReady bool
}

// allCases is the full manifest. Add cases here as new endpoints land.
//
//nolint:revive // the long literal is fine — exhaustiveness matters more.
var allCases = []Case{
	// ---------- admin & project ----------
	{Name: "project", Args: []string{"project"}, BlankPaths: []string{"scannedAt"}},
	{Name: "classes_stats", Args: []string{"classes", "stats"}, BlankPaths: []string{"scanDurationMs", "scannedAt"}},

	// ---------- classes ----------
	{Name: "classes_list_no_filter", Args: []string{"classes", "list"}},
	// Package + name filter against a small, version-stable library package,
	// with --include-libraries (javax.inject:1 is frozen: 6 types).
	{Name: "classes_list_with_filters", Args: []string{
		"classes", "list",
		"--package", "javax.inject",
		"--include-libraries",
	}},
	{Name: "classes_list_paginated", Args: []string{"classes", "list", "--page", "1", "--size", "10"}},

	// ---------- methods ----------
	{Name: "methods_search_all", Args: []string{"methods", "search"}},
	{Name: "methods_search_filtered", Args: []string{
		"methods", "search",
		"--name", "get*",
		"--package", "javax.inject",
		"--include-libraries",
	}},

	// ---------- calls (forward call-site extraction) ----------
	// Route-defining Chain calls in an Action<Chain>: get/post/delete/prefix
	// with their path string constants, plus the synthetic execute(Object)
	// bridge — locks constant-arg capture and overload disambiguation.
	{Name: "calls_users_api_execute", Args: []string{"calls", "sample.api.UsersApi", "--method", "execute"}},
	// Real Blocking.get / Promise.map / Promise.then calls in a handler body.
	{Name: "calls_blocking_handler_handle", Args: []string{"calls", "sample.handlers.BlockingHandler", "--method", "handle"}},

	// ---------- xref (inverse type cross-reference) ----------
	// A project service: signature refs (FIELD/PARAM) plus bytecode CALL_RECEIVER
	// (handlers call userService.findUser) — exercises both passes + aggregates.
	{Name: "xref_userservice", Args: []string{"xref", "sample.handlers.UserService"}},
	// Kind filter narrows the references while aggregates still describe the set.
	{Name: "xref_userservice_field", Args: []string{"xref", "sample.handlers.UserService", "--kind", "FIELD"}},
	// Annotation usages across the project (every @Singleton class).
	{Name: "xref_singleton", Args: []string{"xref", "javax.inject.Singleton"}},

	// ---------- deps (general project-wide dependency graph) ----------
	{Name: "deps_foundation", Args: []string{"deps", "foundation"}},
	{Name: "deps_graph_json", Args: []string{"deps", "graph", "--format", "json"}},
	{Name: "deps_graph_dot", Args: []string{"deps", "graph", "--format", "dot"}},
	// `codelens deps` (no subcommand) — emits the graph.
	{Name: "deps_default_json", Args: []string{"deps", "--format", "json"}},
	{Name: "deps_default_dot", Args: []string{"deps", "--format", "dot"}},

	// ---------- classes (continued) ----------
	// Show a class that doesn't exist — exits 1 with a 404 body. Locks the
	// error-path contract (previously covered by handlers_show_not_found).
	{Name: "classes_show_not_found", Args: []string{"classes", "show", "sample.handlers.NonExistent"}, ExpectExitCode: 1},
	// Show a specific class by FQN (BlockingHandler has @Inject, deps, etc.)
	{Name: "classes_show", Args: []string{"classes", "show", "sample.handlers.BlockingHandler"}},
	// Interfaces-only filter.
	{Name: "classes_list_interfaces", Args: []string{"classes", "list", "--interfaces"}},
	// Subclasses of a (Guice) library base class — AppModule extends AbstractModule.
	{Name: "classes_implementations", Args: []string{"classes", "implementations", "com.google.inject.AbstractModule"}},
	// Class dependencies (both incoming and outgoing).
	{Name: "classes_dependencies", Args: []string{"classes", "dependencies", "sample.handlers.BlockingHandler"}},

	// ---------- annotations ----------
	{Name: "annotations_usages_singleton", Args: []string{"annotations", "usages", "javax.inject.Singleton"}},

	// ---------- methods (continued) ----------
	// Search by return type (project methods returning String).
	{Name: "methods_search_return_type", Args: []string{
		"methods", "search",
		"--return-type", "java.lang.String",
	}},

	// ---------- source ----------
	// Source retrieval for a project class.
	{Name: "source_show", Args: []string{"source", "show", "sample.handlers.SimpleHandler"}},

	// ---------- lint ----------
	// lint_check on the sample fixture — which contains BadFormatting.kt
	// intentionally. Exits 1 and emits JSON describing the violations.
	// Locks the exit-code contract (P2 #1 fix) AND the FileLintResult model
	// (no per-file durationMs, P2 #4 fix).
	{Name: "lint_check_project_with_violations", Args: []string{"lint", "check"}, ExpectExitCode: 1},
	// Single-file lint check on the same offender — exercises the LintFile
	// path and locks the exit-code contract for the single-file mode of
	// the P2 #1 fix (only the project mode is covered above).
	{
		Name:           "lint_check_single_file_with_violations",
		Args:           []string{"lint", "check", "{{PROJECT}}/src/main/kotlin/sample/BadFormatting.kt"},
		ExpectExitCode: 1,
	},
	// lint format --dry-run, project-wide — exercises the corrected
	// FormatProjectResponse model (filesFormatted []string, not fileResults).
	{Name: "lint_format_project_dry_run", Args: []string{"lint", "format", "--dry-run"}},
	// Single-file lint format --dry-run — exercises the FormatFile
	// response model under the writeToFile=false path. Using --dry-run
	// avoids mutating the fixture file across e2e runs.
	{
		Name: "lint_format_single_file_dry_run",
		Args: []string{"lint", "format", "{{PROJECT}}/src/main/kotlin/sample/BadFormatting.kt", "--dry-run"},
	},
}

// springCases run against the richer sample-spring-boot-app fixture, proving
// the general primitives produce useful analysis on a second framework (Spring).
// Goldens live under testdata/golden/spring/.
//
//nolint:revive // exhaustiveness over brevity.
var springCases = []Case{
	// Project counts are deterministic; library/JDK counts and classpath size
	// vary with Spring's (pinned but large) transitive tree, so blank them.
	{Name: "spring_stats", Args: []string{"classes", "stats"}, BlankPaths: []string{
		"scanDurationMs", "scannedAt", "libraryClassCount", "jdkClassCount", "classpathEntryCount",
	}},

	// ---------- classes ----------
	{Name: "spring_classes_list", Args: []string{"classes", "list"}},
	{Name: "spring_classes_show_controller", Args: []string{"classes", "show", "com.example.shop.web.ProductController"}},
	{Name: "spring_classes_list_interfaces", Args: []string{"classes", "list", "--interfaces"}},

	// ---------- implementations & hierarchy ----------
	// Interface + impl pair.
	{Name: "spring_impl_productservice", Args: []string{"classes", "implementations", "com.example.shop.service.ProductService"}},
	// Project repositories extending a Spring Data library interface.
	{Name: "spring_impl_jparepository", Args: []string{"classes", "implementations", "org.springframework.data.jpa.repository.JpaRepository"}},
	// Controller -> abstract BaseController -> Object.
	{Name: "spring_hierarchy_controller", Args: []string{"classes", "hierarchy", "com.example.shop.web.ProductController"}},

	// ---------- annotations ----------
	{Name: "spring_annotations_restcontroller", Args: []string{
		"annotations", "usages", "org.springframework.web.bind.annotation.RestController",
	}},
	{Name: "spring_annotations_service", Args: []string{
		"annotations", "usages", "org.springframework.stereotype.Service",
	}},
	// Meta-annotation match: @RestController classes are found by their @Controller meta.
	{Name: "spring_classes_controller_meta", Args: []string{
		"classes", "list", "--annotation", "org.springframework.stereotype.Controller",
	}},
	// Centralized exception handling (class-level @RestControllerAdvice).
	{Name: "spring_annotations_advice", Args: []string{
		"annotations", "usages", "org.springframework.web.bind.annotation.RestControllerAdvice",
	}},
	// MapStruct DTO<->entity mappers (class-level @Mapper on the interface).
	{Name: "spring_annotations_mapper", Args: []string{
		"annotations", "usages", "org.mapstruct.Mapper",
	}},

	// ---------- methods ----------
	{Name: "spring_methods_get", Args: []string{"methods", "search", "--name", "get*"}},
	// Meta-annotation expansion: every @GetMapping/@PostMapping handler method is
	// found by searching the meta @RequestMapping it composes.
	{Name: "spring_methods_requestmapping", Args: []string{
		"methods", "search", "--annotation", "org.springframework.web.bind.annotation.RequestMapping",
	}},
	// Method-level @Transactional (annotations usages is class-only, so search methods).
	{Name: "spring_methods_transactional", Args: []string{
		"methods", "search", "--annotation", "org.springframework.transaction.annotation.Transactional",
	}},
	// Method-level @PreAuthorize.
	{Name: "spring_methods_preauthorize", Args: []string{
		"methods", "search", "--annotation", "org.springframework.security.access.prepost.PreAuthorize",
	}},

	// ---------- calls (constant string args in @Bean methods) ----------
	// Builder setters with constant JDBC connection strings.
	{Name: "spring_calls_dbconfig", Args: []string{"calls", "com.example.shop.config.DatabaseConfig", "--method", "dataSource"}},
	// Controller method delegating to a service.
	{Name: "spring_calls_controller", Args: []string{"calls", "com.example.shop.web.ProductController", "--method", "create"}},
	// WebFlux functional routing: GET/POST builder calls + path string constants
	// in the @Bean returning a RouterFunction (routes carry no annotations).
	{Name: "spring_calls_router", Args: []string{
		"calls", "com.example.shop.web.CatalogRouter", "--method", "catalogRoutes",
	}},
	// Security posture: top-level SecurityFilterChain structure (csrf /
	// authorizeHttpRequests / httpBasic) — the matcher rules live in a lambda.
	{Name: "spring_calls_security", Args: []string{
		"calls", "com.example.shop.config.SecurityConfig", "--method", "filterChain",
	}},
	// The authorization rules themselves: requestMatchers path constants paired
	// with permitAll/authenticated, inside the authorizeHttpRequests lambda.
	{Name: "spring_calls_security_rules", Args: []string{
		"calls", "com.example.shop.config.SecurityConfig", "--method", "lambda$filterChain$1",
	}},
	// Blocking-in-reactive: a Mono-returning handler that calls Mono.block().
	{Name: "spring_calls_blocking_reactive", Args: []string{
		"calls", "com.example.shop.web.ReactiveController", "--method", "blocking",
	}},

	// ---------- calls --in-methods-* enclosing-method filters (#44) ----------
	// Blocking-in-reactive as a single query: keep only call-sites inside the
	// Mono-returning handlers (one/blocking/stock), excluding the Flux stream().
	{Name: "spring_calls_in_methods_returning", Args: []string{
		"calls", "com.example.shop.web.ReactiveController",
		"--in-methods-returning", "reactor.core.publisher.Mono",
	}},
	// Annotation filter (meta-expanded): only the @GetMapping handlers (list/get),
	// not the @PostMapping ones (create/importProduct).
	{Name: "spring_calls_in_methods_annotated", Args: []string{
		"calls", "com.example.shop.web.ProductController",
		"--in-methods-annotated", "org.springframework.web.bind.annotation.GetMapping",
	}},
	// Composition: --method intersected with an enclosing-method filter scopes to
	// the one matching overload/handler.
	{Name: "spring_calls_method_plus_filter", Args: []string{
		"calls", "com.example.shop.web.ReactiveController",
		"--method", "blocking", "--in-methods-returning", "reactor.core.publisher.Mono",
	}},

	// ---------- xref: blocking vs reactive contrast ----------
	// Blocking path: javax.sql.DataSource (InventoryService + DatabaseConfig).
	{Name: "spring_xref_datasource", Args: []string{"xref", "javax.sql.DataSource"}},
	// Reactive path: Reactor Mono (ReactiveController + ReactiveCatalogService).
	{Name: "spring_xref_mono", Args: []string{"xref", "reactor.core.publisher.Mono"}},
	// A foundation service referenced many ways (FIELD + CALL_RECEIVER).
	{Name: "spring_xref_notification", Args: []string{"xref", "com.example.shop.service.NotificationService"}},

	// ---------- deps ----------
	{Name: "spring_deps_foundation", Args: []string{"deps", "foundation"}},
	{Name: "spring_deps_graph", Args: []string{"deps", "graph", "--format", "json"}},
}

// micronautCases run against the self-contained sample-micronaut-app fixture
// (Micronaut + Flyway + Hikari, Kotlin). They mirror the structural checks the
// plan envisioned for a real Micronaut project, but as deterministic golden
// cases over a committed, version-pinned fixture — no external project.
// Goldens live under testdata/golden/micronaut/.
//
//nolint:revive // exhaustiveness over brevity.
var micronautCases = []Case{
	{Name: "mn_stats", Args: []string{"classes", "stats"}, BlankPaths: []string{
		"scanDurationMs", "scannedAt", "libraryClassCount", "jdkClassCount", "classpathEntryCount",
	}},
	{Name: "mn_classes_list", Args: []string{"classes", "list"}},

	// Micronaut / Jakarta annotations.
	{Name: "mn_annotations_singleton", Args: []string{"annotations", "usages", "jakarta.inject.Singleton"}},
	{Name: "mn_annotations_requires", Args: []string{
		"annotations", "usages", "io.micronaut.context.annotation.Requires",
	}},

	// Implementations: converter interface + Micronaut event-listener.
	{Name: "mn_impl_converter", Args: []string{
		"classes", "implementations", "us.charliek.flyway.converter.R2dbcToJdbcConverter",
	}},
	{Name: "mn_impl_listener", Args: []string{
		"classes", "implementations", "io.micronaut.context.event.ApplicationEventListener",
	}},

	// Exception hierarchy reaches RuntimeException/Exception.
	{Name: "mn_hierarchy_exception", Args: []string{
		"classes", "hierarchy", "us.charliek.flyway.exception.FlywayR2dbcMigrationException",
	}},

	// Xref of the JDBC / pooling / migration library types.
	{Name: "mn_xref_datasource", Args: []string{"xref", "javax.sql.DataSource"}},
	{Name: "mn_xref_sqlexception", Args: []string{"xref", "java.sql.SQLException"}},
	{Name: "mn_xref_hikari_config", Args: []string{"xref", "com.zaxxer.hikari.HikariConfig"}},
	{Name: "mn_xref_flyway", Args: []string{"xref", "org.flywaydb.core.Flyway"}},

	// Calls: SLF4J + System.currentTimeMillis + log string constants in the
	// migrator; Hikari config setters + HikariDataSource construction.
	{Name: "mn_calls_migrator", Args: []string{
		"calls", "us.charliek.flyway.FlywayR2dbcMigrator", "--method", "onApplicationEvent",
	}},
	{Name: "mn_calls_todatasource", Args: []string{
		"calls", "us.charliek.flyway.converter.JdbcConnectionInfo", "--method", "toDataSource",
	}},

	// Foundation: shared config / converter types with multiple dependents.
	{Name: "mn_deps_foundation", Args: []string{"deps", "foundation"}},
}
