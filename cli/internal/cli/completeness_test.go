package cli

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	clierrors "github.com/charliek/codelens/cli/internal/errors"
	"github.com/spf13/cobra"
)

// tableAnalysisCommands are the runnable commands that must render a
// human-readable table (via withRenderedServer/emit + a render.* function).
var tableAnalysisCommands = map[string]bool{
	"codelens classes list":            true,
	"codelens classes show":            true,
	"codelens classes stats":           true,
	"codelens classes implementations": true,
	"codelens classes hierarchy":       true,
	"codelens classes dependencies":    true,
	"codelens methods search":          true,
	"codelens calls":                   true,
	"codelens xref":                    true,
	"codelens annotations usages":      true,
	"codelens source show":             true,
	"codelens source method":           true,
	"codelens deps":                    true,
	"codelens deps graph":              true,
	"codelens deps foundation":         true,
	"codelens lint check":              true,
	"codelens lint format":             true,
	"codelens project":                 true,
}

// lifecycleOrUtilityCommands manage the server or print local state. They emit
// their own tables inline (emitLifecycle) or are pure utilities.
var lifecycleOrUtilityCommands = map[string]bool{
	"codelens version": true,
	"codelens start":   true,
	"codelens stop":    true,
	"codelens status":  true,
	"codelens restart": true,
	"codelens refresh": true,
	"codelens list":    true,
}

func leafCommandPaths(root *cobra.Command) []string {
	var paths []string
	var walk func(c *cobra.Command)
	walk = func(c *cobra.Command) {
		if c.RunE != nil || c.Run != nil {
			paths = append(paths, c.CommandPath())
		}
		for _, sub := range c.Commands() {
			walk(sub)
		}
	}
	walk(root)
	return paths
}

// TestEveryLeafCommandIsClassified fails when a new runnable command is added
// without being classified — forcing whoever adds it to either wire a table
// renderer (and list it in tableAnalysisCommands) or mark it lifecycle/utility.
// This is the guard that keeps the "every command has a table" sweep complete.
func TestEveryLeafCommandIsClassified(t *testing.T) {
	for _, p := range leafCommandPaths(newRootCmd()) {
		if !tableAnalysisCommands[p] && !lifecycleOrUtilityCommands[p] {
			t.Errorf("command %q is unclassified.\n"+
				"If it queries the server, wire a render.* function via withRenderedServer/emit "+
				"and add it to tableAnalysisCommands.\n"+
				"Otherwise add it to lifecycleOrUtilityCommands.", p)
		}
	}
}

// forceTTY makes resolveMode pick table output regardless of the real stdout
// (which is a pipe under `go test`).
func forceTTY(t *testing.T) {
	t.Helper()
	prev := stdoutIsTTY
	stdoutIsTTY = func() bool { return true }
	t.Cleanup(func() { stdoutIsTTY = prev })
}

func looksLikeJSON(s string) bool {
	s = strings.TrimSpace(s)
	return strings.HasPrefix(s, "{") || strings.HasPrefix(s, "[")
}

// TestAnalysisCommandsRenderTableOnTTY drives a representative command per
// result type (RawMessage table, source-as-code, graph summary, typed lint
// struct) end-to-end through cobra → emit → resolveMode(TTY) → render, and
// asserts the output is a table, not JSON. This proves the wiring and the
// stdoutIsTTY override; the per-renderer shapes are covered in package render.
func TestAnalysisCommandsRenderTableOnTTY(t *testing.T) {
	forceTTY(t)
	cases := []struct {
		name    string
		args    []string
		payload string
	}{
		{
			name:    "classes_list",
			args:    []string{"classes", "list"},
			payload: `{"classes":[{"fqn":"sample.Foo","simpleName":"Foo","packageName":"sample","source":"PROJECT","methodCount":1,"fieldCount":0,"isInterface":false,"isAnnotation":false,"isEnum":false,"isAbstract":false}],"page":0,"pageSize":50,"totalCount":1,"totalPages":1,"appliedFilter":{"source":"PROJECT"}}`,
		},
		{
			name:    "calls",
			args:    []string{"calls", "sample.Foo"},
			payload: `{"fqn":"sample.Foo","methods":[{"methodName":"m","descriptor":"()V","calls":[{"ownerType":"a.B","methodName":"x","descriptor":"()V","lineNumber":1,"isInterface":false,"constantArgs":[]}]}]}`,
		},
		{
			name:    "source_show",
			args:    []string{"source", "show", "sample.Foo"},
			payload: `{"source":{"content":"class Foo {}\n","fqn":"sample.Foo","filePath":"/x/Foo.java","language":"JAVA","lineCount":1}}`,
		},
		{
			name:    "deps",
			args:    []string{"deps"},
			payload: `{"nodeCount":1,"edgeCount":0,"nodes":[{"fqn":"sample.Foo","inDegree":0,"outDegree":0}]}`,
		},
		{
			name:    "lint_check",
			args:    []string{"lint", "check", "/x/Foo.kt"},
			payload: `{"filePath":"/x/Foo.kt","errors":[],"errorCount":0,"durationMs":1}`,
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			proj := gradleProjectDir(t)
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				_, _ = w.Write([]byte(tc.payload))
			}))
			defer srv.Close()
			withMockClient(t, srv)
			installFake(t, &fakeLifecycle{findResult: mustReady(proj, 8080)})

			res := runCLI(t, append(tc.args, "--project", proj)...)
			if res.exitCode != clierrors.Success {
				t.Fatalf("exit=%d stderr=%s", res.exitCode, res.stderr)
			}
			if looksLikeJSON(res.stdout) {
				t.Errorf("expected table output on a TTY, got JSON:\n%s", res.stdout)
			}
			if strings.TrimSpace(res.stdout) == "" {
				t.Errorf("expected non-empty table output")
			}
		})
	}
}

// TestJSONFlagForcesJSONEvenOnTTY confirms --json wins over TTY autodetect.
func TestJSONFlagForcesJSONEvenOnTTY(t *testing.T) {
	forceTTY(t)
	proj := gradleProjectDir(t)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte(`{"classes":[],"page":0,"pageSize":50,"totalCount":0,"totalPages":0,"appliedFilter":{"source":"PROJECT"}}`))
	}))
	defer srv.Close()
	withMockClient(t, srv)
	installFake(t, &fakeLifecycle{findResult: mustReady(proj, 8080)})

	res := runCLI(t, "classes", "list", "--project", proj, "--json")
	if res.exitCode != clierrors.Success {
		t.Fatalf("exit=%d stderr=%s", res.exitCode, res.stderr)
	}
	if !looksLikeJSON(res.stdout) {
		t.Errorf("--json must force JSON even on a TTY; got:\n%s", res.stdout)
	}
}

// TestJSONAndTableAreMutuallyExclusive confirms cobra rejects both flags and
// the error maps to the InvalidUsage exit code.
func TestJSONAndTableAreMutuallyExclusive(t *testing.T) {
	proj := gradleProjectDir(t)
	installFake(t, &fakeLifecycle{findResult: mustReady(proj, 8080)})

	res := runCLI(t, "classes", "list", "--project", proj, "--json", "--table")
	if res.exitCode != clierrors.InvalidUsage {
		t.Fatalf("expected InvalidUsage (2); got %d, stderr=%s", res.exitCode, res.stderr)
	}
}
