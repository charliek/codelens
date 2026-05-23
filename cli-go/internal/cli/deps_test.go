package cli

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/charliek/codelens/cli-go/internal/client"
	clierrors "github.com/charliek/codelens/cli-go/internal/errors"
)

// `codelens deps` (no subcommand) is a documented Python command that hits
// /api/v1/ratpack/dependencies. Locked here so the Go port stays in parity.
func TestDeps_Default_JSONHitsAnalysisEndpoint(t *testing.T) {
	proj := gradleProjectDir(t)
	var lastPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		lastPath = r.URL.Path
		_, _ = w.Write([]byte(`{"summary":"OK"}`))
	}))
	defer srv.Close()
	withMockClient(t, srv)
	installFake(t, &fakeLifecycle{findResult: mustReady(proj, 8080)})

	res := runCLI(t, "deps", "--project", proj, "--format", "json")
	if res.exitCode != clierrors.Success {
		t.Fatalf("exit=%d, stderr=%s", res.exitCode, res.stderr)
	}
	if lastPath != "/api/v1/ratpack/dependencies" {
		t.Errorf("wrong endpoint: %s (expected /api/v1/ratpack/dependencies)", lastPath)
	}
	if !strings.Contains(res.stdout, `"summary"`) {
		t.Errorf("expected analysis JSON in stdout; got %s", res.stdout)
	}
}

func TestDeps_Default_DOTReturnsRawBody(t *testing.T) {
	proj := gradleProjectDir(t)
	var lastQuery string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		lastQuery = r.URL.RawQuery
		_, _ = w.Write([]byte("digraph X { A -> B }\n"))
	}))
	defer srv.Close()
	withMockClient(t, srv)
	installFake(t, &fakeLifecycle{findResult: mustReady(proj, 8080)})

	res := runCLI(t, "deps", "--project", proj, "--format", "dot")
	if res.exitCode != clierrors.Success {
		t.Fatalf("exit=%d, stderr=%s", res.exitCode, res.stderr)
	}
	if lastQuery != "format=dot" {
		t.Errorf("expected ?format=dot; got %q", lastQuery)
	}
	if !strings.HasPrefix(res.stdout, "digraph X") {
		t.Errorf("expected raw DOT body; got %q", res.stdout)
	}
}

// Subcommands still work — make sure the default RunE doesn't shadow them.
func TestDeps_FoundationSubcommandStillRoutes(t *testing.T) {
	proj := gradleProjectDir(t)
	var lastPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		lastPath = r.URL.Path
		_, _ = w.Write([]byte(`{"foundation":[]}`))
	}))
	defer srv.Close()
	withMockClient(t, srv)
	installFake(t, &fakeLifecycle{findResult: mustReady(proj, 8080)})

	res := runCLI(t, "deps", "foundation", "--project", proj)
	if res.exitCode != clierrors.Success {
		t.Fatalf("exit=%d, stderr=%s", res.exitCode, res.stderr)
	}
	if lastPath != "/api/v1/ratpack/dependencies/foundation" {
		t.Errorf("subcommand routed wrong: %s", lastPath)
	}
}

// silence unused-import warnings if test helpers from other files relocate.
var _ = client.NewClient
