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
	{Name: "classes_list_with_filters", Args: []string{
		"classes", "list",
		"--package", "ratpack.*",
		"--name", "*Handler",
		"--include-libraries",
	}},
	{Name: "classes_list_paginated", Args: []string{"classes", "list", "--page", "1", "--size", "10"}},

	// ---------- methods ----------
	{Name: "methods_search_all", Args: []string{"methods", "search"}},
	{Name: "methods_search_filtered", Args: []string{
		"methods", "search",
		"--name", "get*",
		"--package", "ratpack.*",
		"--include-libraries",
	}},

	// ---------- calls (forward call-site extraction) ----------
	// Route-defining Chain calls in an Action<Chain>: get/post/delete/prefix
	// with their path string constants, plus the synthetic execute(Object)
	// bridge — locks constant-arg capture and overload disambiguation.
	{Name: "calls_users_api_execute", Args: []string{"calls", "sample.api.UsersApi", "--method", "execute"}},
	// Real Blocking.get / Promise.map / Promise.then calls in a handler body.
	{Name: "calls_blocking_handler_handle", Args: []string{"calls", "sample.handlers.BlockingHandler", "--method", "handle"}},

	// ---------- handlers ----------
	{Name: "handlers_list", Args: []string{"handlers", "list"}},
	{Name: "handlers_list_filtered", Args: []string{"handlers", "list", "--include-libraries"}},

	// ---------- promises ----------
	{Name: "promises_summary", Args: []string{"promises", "summary"}},
	{Name: "promises_search_omit_filters", Args: []string{"promises", "search"}},
	{Name: "promises_search_true_filters", Args: []string{
		"promises", "search",
		"--blocking", "--async", "--fork",
	}},
	{Name: "promises_search_false_filters", Args: []string{
		"promises", "search",
		"--no-blocking", "--no-async", "--no-fork",
	}},

	// ---------- migration ----------
	{Name: "migration_order", Args: []string{"migration", "order"}},
	{Name: "migration_complexity_summary", Args: []string{"migration", "complexity"}},

	// ---------- modules ----------
	{Name: "modules_list", Args: []string{"modules", "list"}},

	// ---------- integrations ----------
	{Name: "integrations_list", Args: []string{"integrations", "list"}},
	{Name: "integrations_list_filtered", Args: []string{
		"integrations", "list",
		"--type", "HTTP_CLIENT",
		"--sub-type", "RATPACK_HTTP_CLIENT",
	}},

	// ---------- antipatterns ----------
	{Name: "antipatterns_scan", Args: []string{"antipatterns", "scan"}},
	{Name: "antipatterns_scan_filtered", Args: []string{"antipatterns", "scan", "--severity", "WARNING"}},

	// ---------- routes ----------
	{Name: "routes_list", Args: []string{"routes", "list"}},
	{Name: "routes_tree", Args: []string{"routes", "tree"}},
	{Name: "routes_spring", Args: []string{"routes", "spring"}},

	// ---------- deps ----------
	{Name: "deps_foundation", Args: []string{"deps", "foundation"}},
	{Name: "deps_quickwins", Args: []string{"deps", "quickwins"}},
	{Name: "deps_graph_json", Args: []string{"deps", "graph", "--format", "json"}},
	{Name: "deps_graph_dot", Args: []string{"deps", "graph", "--format", "dot"}},
	// `codelens deps` (no subcommand) — locked default-subcommand behavior.
	{Name: "deps_default_json", Args: []string{"deps", "--format", "json"}},
	{Name: "deps_default_dot", Args: []string{"deps", "--format", "dot"}},

	// ---------- handlers (continued) ----------
	// Client-side --missing-inject filter.
	{Name: "handlers_list_missing_inject", Args: []string{"handlers", "list", "--missing-inject"}},
	// Tier filter — exercises the query-param pass-through.
	{Name: "handlers_list_tier_low", Args: []string{"handlers", "list", "--tier", "LOW"}},
	// Show a handler that doesn't exist — exits 1 with a 404 body.
	// Locks the error-path contract.
	{Name: "handlers_show_not_found", Args: []string{"handlers", "show", "sample.handlers.NonExistent"}, ExpectExitCode: 1},

	// ---------- classes (continued) ----------
	// Show a specific class by FQN (BlockingHandler has @Inject, deps, etc.)
	{Name: "classes_show", Args: []string{"classes", "show", "sample.handlers.BlockingHandler"}},
	// Interfaces-only filter.
	{Name: "classes_list_interfaces", Args: []string{"classes", "list", "--interfaces"}},
	// Implementations of Handler interface.
	{Name: "classes_implementations", Args: []string{"classes", "implementations", "ratpack.handling.Handler"}},
	// Class dependencies (both incoming and outgoing).
	{Name: "classes_dependencies", Args: []string{"classes", "dependencies", "sample.handlers.BlockingHandler"}},

	// ---------- annotations ----------
	{Name: "annotations_usages_singleton", Args: []string{"annotations", "usages", "javax.inject.Singleton"}},

	// ---------- methods (continued) ----------
	// Search by return type.
	{Name: "methods_search_return_type", Args: []string{
		"methods", "search",
		"--return-type", "ratpack.exec.Promise",
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
