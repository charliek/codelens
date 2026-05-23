package cli

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/charliek/codelens/cli/internal/client"
	clierrors "github.com/charliek/codelens/cli/internal/errors"
)

func readReqBody(r *http.Request) ([]byte, error) {
	if r.Body == nil {
		return nil, nil
	}
	defer func() { _ = r.Body.Close() }()
	buf := make([]byte, 0, 4096)
	tmp := make([]byte, 4096)
	for {
		n, err := r.Body.Read(tmp)
		if n > 0 {
			buf = append(buf, tmp[:n]...)
		}
		if err != nil {
			return buf, nil
		}
	}
}

// =============================================================================
// lint check exit code
// =============================================================================

func TestLintCheck_FileWithViolations_Exit1(t *testing.T) {
	proj := gradleProjectDir(t)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte(`{
			"filePath": "/tmp/Foo.kt",
			"errors": [{"line": 1, "col": 1, "ruleId": "no-wildcard-imports", "detail": "x", "canBeAutoCorrected": true}],
			"errorCount": 1,
			"durationMs": 5
		}`))
	}))
	defer srv.Close()

	withMockClient(t, srv)
	installFake(t, &fakeLifecycle{findResult: mustReady(proj, 8080)})

	res := runCLI(t, "lint", "check", "/tmp/Foo.kt", "--project", proj)
	if res.exitCode != clierrors.GeneralError {
		t.Fatalf("expected exit 1 (GeneralError); got %d, stderr=%s", res.exitCode, res.stderr)
	}
	// JSON must still be on stdout — the user can pipe to jq.
	var out map[string]any
	if err := json.Unmarshal([]byte(res.stdout), &out); err != nil {
		t.Fatalf("stdout not JSON: %s", res.stdout)
	}
	if out["errorCount"] != float64(1) {
		t.Errorf("errorCount missing from stdout: %v", out)
	}
}

func TestLintCheck_FileClean_Exit0(t *testing.T) {
	proj := gradleProjectDir(t)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte(`{"filePath":"/tmp/Foo.kt","errors":[],"errorCount":0,"durationMs":3}`))
	}))
	defer srv.Close()
	withMockClient(t, srv)
	installFake(t, &fakeLifecycle{findResult: mustReady(proj, 8080)})

	res := runCLI(t, "lint", "check", "/tmp/Foo.kt", "--project", proj)
	if res.exitCode != clierrors.Success {
		t.Errorf("expected exit 0; got %d, stderr=%s", res.exitCode, res.stderr)
	}
}

func TestLintCheck_ProjectWithViolations_Exit1(t *testing.T) {
	proj := gradleProjectDir(t)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte(`{
			"projectPath": "/tmp",
			"fileResults": [{"filePath":"/tmp/A.kt","errors":[],"errorCount":3}],
			"filesScanned": 4,
			"filesWithErrors": 1,
			"totalErrorCount": 3,
			"durationMs": 99
		}`))
	}))
	defer srv.Close()
	withMockClient(t, srv)
	installFake(t, &fakeLifecycle{findResult: mustReady(proj, 8080)})

	res := runCLI(t, "lint", "check", "--project", proj)
	if res.exitCode != clierrors.GeneralError {
		t.Errorf("expected exit 1; got %d", res.exitCode)
	}
}

// =============================================================================
// lint format default writes
// =============================================================================

func TestLintFormat_File_NoFlagsWritesByDefault(t *testing.T) {
	proj := gradleProjectDir(t)
	var lastBody []byte
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		lastBody, _ = readReqBody(r)
		_, _ = w.Write([]byte(`{"filePath":"/tmp/x.kt","formattedContent":null,"hasChanges":false,"remainingErrors":[],"durationMs":1}`))
	}))
	defer srv.Close()
	withMockClient(t, srv)
	installFake(t, &fakeLifecycle{findResult: mustReady(proj, 8080)})

	res := runCLI(t, "lint", "format", "/tmp/x.kt", "--project", proj)
	if res.exitCode != clierrors.Success {
		t.Fatalf("exit = %d, stderr=%s", res.exitCode, res.stderr)
	}
	var body map[string]any
	if err := json.Unmarshal(lastBody, &body); err != nil {
		t.Fatalf("bad body: %s", lastBody)
	}
	if body["writeToFile"] != true {
		t.Errorf("expected writeToFile=true by default; got body=%v", body)
	}
}

func TestLintFormat_File_DryRunDoesNotWrite(t *testing.T) {
	proj := gradleProjectDir(t)
	var lastBody []byte
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		lastBody, _ = readReqBody(r)
		_, _ = w.Write([]byte(`{"filePath":"/tmp/x.kt","formattedContent":null,"hasChanges":false,"remainingErrors":[],"durationMs":1}`))
	}))
	defer srv.Close()
	withMockClient(t, srv)
	installFake(t, &fakeLifecycle{findResult: mustReady(proj, 8080)})

	res := runCLI(t, "lint", "format", "/tmp/x.kt", "--project", proj, "--dry-run")
	if res.exitCode != clierrors.Success {
		t.Fatalf("exit = %d", res.exitCode)
	}
	var body map[string]any
	if err := json.Unmarshal(lastBody, &body); err != nil {
		t.Fatalf("bad body: %s", lastBody)
	}
	if body["writeToFile"] != false {
		t.Errorf("--dry-run should send writeToFile=false; got body=%v", body)
	}
}

// =============================================================================
// helpers
// =============================================================================

// withMockClient redirects dataClientFactory at an httptest server for the
// remainder of the test.
func withMockClient(t *testing.T, srv *httptest.Server) {
	t.Helper()
	prev := dataClientFactory
	dataClientFactory = func(_ string, _ int) *client.Client {
		return &client.Client{BaseURL: srv.URL, HTTP: srv.Client()}
	}
	t.Cleanup(func() { dataClientFactory = prev })
}

// gradleProjectDir / fakeLifecycle / mustReady / runCLI / installFake
// are all defined in lifecycle_test.go.
