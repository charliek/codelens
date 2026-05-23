// Package parity runs the Go CLI and the Python CLI against the same
// running server and asserts that their --json output is identical after
// normalizing mutable fields (timestamps, uptimes, pid, port).
//
// This is the executable contract that proves the Go port is a behavioral
// drop-in for the Python CLI. Adding a new endpoint to the CLI should
// usually mean adding a Case here.
package parity

// Case is one (command, args) tuple to exercise on both CLIs.
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
}
