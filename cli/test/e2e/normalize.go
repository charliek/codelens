package e2e

import (
	"encoding/json"
	"sort"
	"strings"
)

// defaultBlankPaths is applied to every case in addition to per-case
// BlankPaths. Covers mutable fields that change between CLI invocations:
// server pid/port (allocated dynamically), timestamps and durations from
// /admin/info merges, and the project path (which varies with the temp
// HOME used by the harness).
var defaultBlankPaths = []string{
	"pid",
	"port",
	"uptime",
	"idleDuration",
	"startedAt",
	"lastActivityAt",
	"scanDurationMs",
	"scannedAt",
	"durationMs",
}

// normalizeJSON parses b, blanks every path listed in `paths`, then
// re-serializes for comparison. Returns the original bytes on parse error
// (DOT output and similar non-JSON payloads).
//
// A "path" is a dot-separated sequence of keys; the special segment "*"
// applies the remaining suffix to every element of an array (or every
// value of a map).
func normalizeJSON(b []byte, paths []string) []byte {
	if len(b) == 0 {
		return b
	}
	var v any
	if err := json.Unmarshal(b, &v); err != nil {
		return b
	}
	all := append(append([]string{}, defaultBlankPaths...), paths...)
	for _, p := range all {
		blankPath(v, strings.Split(p, "."))
	}
	out, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		return b
	}
	return out
}

// blankPath walks `v` following `parts`. When it reaches the leaf, the
// containing map's value is replaced with json.RawMessage("__BLANK__").
// "*" matches any array index or map key at that position.
func blankPath(v any, parts []string) {
	if len(parts) == 0 {
		return
	}
	head := parts[0]
	rest := parts[1:]

	switch node := v.(type) {
	case map[string]any:
		if head == "*" {
			for k := range node {
				if len(rest) == 0 {
					node[k] = "__BLANK__"
				} else {
					blankPath(node[k], rest)
				}
			}
			return
		}
		if _, ok := node[head]; !ok {
			return
		}
		if len(rest) == 0 {
			node[head] = "__BLANK__"
			return
		}
		blankPath(node[head], rest)

	case []any:
		if head != "*" {
			return
		}
		for i := range node {
			if len(rest) == 0 {
				node[i] = "__BLANK__"
			} else {
				blankPath(node[i], rest)
			}
		}
	}
}

// pathReplacement maps a machine-specific absolute path to a stable
// placeholder used in committed golden fixtures.
type pathReplacement struct {
	from string
	to   string
}

// templatizePaths rewrites machine-specific absolute paths in b (the temp
// HOME, the server JAR, the project path, the repo root) to stable
// placeholders so golden fixtures are portable across machines and CI. It
// runs after JSON normalization, on either marshaled JSON or raw non-JSON
// output. Replacements are applied longest-`from`-first so a path nested
// under another (e.g. the JAR or project under the repo root) is matched
// before its parent.
func templatizePaths(b []byte, repls []pathReplacement) []byte {
	ordered := append([]pathReplacement(nil), repls...)
	sort.SliceStable(ordered, func(i, j int) bool {
		return len(ordered[i].from) > len(ordered[j].from)
	})
	s := string(b)
	for _, r := range ordered {
		if r.from == "" {
			continue
		}
		s = strings.ReplaceAll(s, r.from, r.to)
	}
	return []byte(s)
}
