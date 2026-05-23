package cli

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	clierrors "github.com/charliek/codelens/cli-go/internal/errors"
	"github.com/charliek/codelens/cli-go/internal/server"
	"github.com/charliek/codelens/cli-go/internal/state"
)

// fakeLifecycle stands in for *server.Service so unit tests don't spawn a JVM.
type fakeLifecycle struct {
	findResult  *state.ServerState
	findErr     error
	startResult *state.ServerState
	startErr    error
	stopOK      bool
	stopErr     error
	listResult  []*state.ServerState
	listErr     error

	startCalls []server.StartOptions
	stopCalls  []struct {
		Path  string
		Force bool
	}
}

func (f *fakeLifecycle) Find(_ string) (*state.ServerState, error) {
	return f.findResult, f.findErr
}
func (f *fakeLifecycle) Start(_ context.Context, o server.StartOptions) (*state.ServerState, error) {
	f.startCalls = append(f.startCalls, o)
	return f.startResult, f.startErr
}
func (f *fakeLifecycle) Stop(p string, force bool) (bool, error) {
	f.stopCalls = append(f.stopCalls, struct {
		Path  string
		Force bool
	}{p, force})
	return f.stopOK, f.stopErr
}
func (f *fakeLifecycle) ListAll() ([]*state.ServerState, error) {
	return f.listResult, f.listErr
}

// noopAdminClient implements adminClient and never errors.
type noopAdminClient struct{}

func (noopAdminClient) Info(_ context.Context) (json.RawMessage, error) {
	return json.RawMessage(`{"uptime":"1m 30s","idleDuration":"5s"}`), nil
}
func (noopAdminClient) Close() {}

// installFake makes the package-level factories produce the supplied fake.
func installFake(t *testing.T, f *fakeLifecycle) {
	t.Helper()
	prevLC := lifecycleFactory
	prevCF := clientFactory
	lifecycleFactory = func() (LifecycleService, error) { return f, nil }
	clientFactory = func(_ string, _ int) adminClient { return noopAdminClient{} }
	t.Cleanup(func() {
		lifecycleFactory = prevLC
		clientFactory = prevCF
	})
}

// gradleProjectDir creates a temp dir with an empty build.gradle.kts so the
// project-path validation passes.
func gradleProjectDir(t *testing.T) string {
	t.Helper()
	tmp := t.TempDir()
	if err := os.WriteFile(filepath.Join(tmp, "build.gradle.kts"), []byte(""), 0o644); err != nil {
		t.Fatal(err)
	}
	return tmp
}

// run helpers: build a fresh root command, run it with args, capture stdout
// and the resulting exit code (translated from *clierrors.CLIError).
type runResult struct {
	stdout   string
	stderr   string
	exitCode clierrors.ExitCode
	err      error
}

func runCLI(t *testing.T, args ...string) runResult {
	t.Helper()
	root := newRootCmd()
	outBuf := &bytes.Buffer{}
	errBuf := &bytes.Buffer{}
	root.SetOut(outBuf)
	root.SetErr(errBuf)
	root.SetArgs(args)
	err := root.Execute()
	res := runResult{stdout: outBuf.String(), stderr: errBuf.String(), err: err}
	if err == nil {
		res.exitCode = clierrors.Success
		return res
	}
	var ce *clierrors.CLIError
	switch {
	case errors.As(err, &ce):
		res.exitCode = ce.Code
	case isUsageError(err):
		res.exitCode = clierrors.InvalidUsage
	default:
		res.exitCode = clierrors.GeneralError
	}
	return res
}

func mustReady(projectPath string, port int) *state.ServerState {
	now := state.PythonTime{Time: time.Date(2026, 5, 23, 4, 0, 0, 0, time.UTC)}
	return &state.ServerState{
		PID: 12345, Port: port, Host: "127.0.0.1",
		ProjectPath: projectPath, ProjectName: filepath.Base(projectPath),
		StartedAt: now, LastActivityAt: now,
		IdleTimeout: "30m", Status: state.StatusReady, ServerMode: state.ServerModeJAR,
		Version: "9.9.9-test",
	}
}

// ============================================================================
// status
// ============================================================================

func TestStatus_NoServerRunning_EmitsRunningFalseWithProject(t *testing.T) {
	proj := gradleProjectDir(t)
	installFake(t, &fakeLifecycle{findResult: nil})

	res := runCLI(t, "status", "--project", proj)
	if res.exitCode != clierrors.Success {
		t.Fatalf("exit = %d, stderr=%s", res.exitCode, res.stderr)
	}
	var got map[string]any
	if err := json.Unmarshal([]byte(res.stdout), &got); err != nil {
		t.Fatalf("not JSON: %s", res.stdout)
	}
	if got["running"] != false {
		t.Errorf("running = %v, want false", got["running"])
	}
	if got["project"] != proj {
		t.Errorf("project = %v, want %s", got["project"], proj)
	}
}

func TestStatus_ServerRunning_NoRunningFieldOnSuccessPath(t *testing.T) {
	proj := gradleProjectDir(t)
	installFake(t, &fakeLifecycle{findResult: mustReady(proj, 8080)})

	res := runCLI(t, "status", "--project", proj)
	if res.exitCode != clierrors.Success {
		t.Fatalf("exit = %d, stderr=%s", res.exitCode, res.stderr)
	}
	var got map[string]any
	if err := json.Unmarshal([]byte(res.stdout), &got); err != nil {
		t.Fatalf("not JSON: %s", res.stdout)
	}
	if _, has := got["running"]; has {
		t.Errorf("running field should be ABSENT on success path; got %v", got)
	}
	if got["port"] != float64(8080) {
		t.Errorf("port wrong: %v", got["port"])
	}
	// Live-info fields merged in by clientFactory fake:
	if got["uptime"] != "1m 30s" {
		t.Errorf("uptime not merged: %v", got)
	}
}

// ============================================================================
// stop
// ============================================================================

func TestStop_RunningServer_EmitsStoppedTrue(t *testing.T) {
	proj := gradleProjectDir(t)
	fake := &fakeLifecycle{stopOK: true}
	installFake(t, fake)

	res := runCLI(t, "stop", "--project", proj)
	if res.exitCode != clierrors.Success {
		t.Fatalf("exit = %d, stderr=%s", res.exitCode, res.stderr)
	}
	var got map[string]any
	_ = json.Unmarshal([]byte(res.stdout), &got)
	if got["stopped"] != true || got["project"] != proj {
		t.Errorf("payload = %v", got)
	}
	if len(fake.stopCalls) != 1 || fake.stopCalls[0].Force {
		t.Errorf("stop call = %+v", fake.stopCalls)
	}
}

func TestStop_ForceFlagPropagates(t *testing.T) {
	proj := gradleProjectDir(t)
	fake := &fakeLifecycle{stopOK: true}
	installFake(t, fake)

	res := runCLI(t, "stop", "--project", proj, "--force")
	if res.exitCode != clierrors.Success {
		t.Fatalf("exit = %d", res.exitCode)
	}
	if !fake.stopCalls[0].Force {
		t.Errorf("--force did not propagate")
	}
}

func TestStop_NoServer_EmitsStoppedFalse(t *testing.T) {
	proj := gradleProjectDir(t)
	installFake(t, &fakeLifecycle{stopOK: false})

	res := runCLI(t, "stop", "--project", proj)
	if res.exitCode != clierrors.Success {
		t.Fatalf("exit = %d, stderr=%s", res.exitCode, res.stderr)
	}
	var got map[string]any
	_ = json.Unmarshal([]byte(res.stdout), &got)
	if got["stopped"] != false {
		t.Errorf("stopped should be false; got %v", got)
	}
}

// ============================================================================
// start / restart
// ============================================================================

func TestStart_AlreadyReady_ReturnsExistingState(t *testing.T) {
	proj := gradleProjectDir(t)
	existing := mustReady(proj, 8080)
	fake := &fakeLifecycle{findResult: existing}
	installFake(t, fake)

	res := runCLI(t, "start", "--project", proj)
	if res.exitCode != clierrors.Success {
		t.Fatalf("exit = %d, stderr=%s", res.exitCode, res.stderr)
	}
	// No Start call when ready already.
	if len(fake.startCalls) != 0 {
		t.Errorf("Start should not be called when server is ready; got %+v", fake.startCalls)
	}
	var got map[string]any
	_ = json.Unmarshal([]byte(res.stdout), &got)
	if got["port"] != float64(8080) {
		t.Errorf("port = %v", got["port"])
	}
}

func TestStart_TimeoutExitsWith5(t *testing.T) {
	proj := gradleProjectDir(t)
	installFake(t, &fakeLifecycle{startErr: context.DeadlineExceeded})

	res := runCLI(t, "start", "--project", proj)
	if res.exitCode != clierrors.Timeout {
		t.Errorf("expected Timeout (5); got %d, stderr=%s", res.exitCode, res.stderr)
	}
}

func TestStart_ServerErrorExitsWith4(t *testing.T) {
	proj := gradleProjectDir(t)
	installFake(t, &fakeLifecycle{startErr: errors.New("scan failed")})

	res := runCLI(t, "start", "--project", proj)
	if res.exitCode != clierrors.ServerError {
		t.Errorf("expected ServerError (4); got %d", res.exitCode)
	}
}

// ============================================================================
// refresh
// ============================================================================

func TestRefresh_NoServerExitsWith7(t *testing.T) {
	proj := gradleProjectDir(t)
	installFake(t, &fakeLifecycle{findResult: nil})

	res := runCLI(t, "refresh", "--project", proj)
	if res.exitCode != clierrors.NotRunning {
		t.Errorf("expected NotRunning (7); got %d", res.exitCode)
	}
}

func TestRefresh_RunningServerPostsAndReturnsResponseJSON(t *testing.T) {
	proj := gradleProjectDir(t)
	// Spin up a small HTTP server that responds to the refresh POST.
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/v1/project/refresh" && r.Method == "POST" {
			_, _ = w.Write([]byte(`{"name":"sample","status":"READY"}`))
			return
		}
		http.NotFound(w, r)
	}))
	defer srv.Close()
	// Parse host/port out of httptest URL.
	host, port := parseURL(t, srv.URL)
	st := mustReady(proj, port)
	st.Host = host
	installFake(t, &fakeLifecycle{findResult: st})

	res := runCLI(t, "refresh", "--project", proj)
	if res.exitCode != clierrors.Success {
		t.Fatalf("exit = %d, stderr=%s", res.exitCode, res.stderr)
	}
	var got map[string]any
	_ = json.Unmarshal([]byte(res.stdout), &got)
	if got["status"] != "READY" {
		t.Errorf("payload = %v", got)
	}
}

// ============================================================================
// list
// ============================================================================

func TestList_NoServersEmitsEmptyArray(t *testing.T) {
	installFake(t, &fakeLifecycle{listResult: nil})

	// list doesn't need a project (and shouldn't require one).
	res := runCLI(t, "list")
	if res.exitCode != clierrors.Success {
		t.Fatalf("exit = %d, stderr=%s", res.exitCode, res.stderr)
	}
	var got map[string]any
	_ = json.Unmarshal([]byte(res.stdout), &got)
	servers, ok := got["servers"].([]any)
	if !ok || len(servers) != 0 {
		t.Errorf("servers = %v (want empty array)", got["servers"])
	}
}

func TestList_RunningServersEmitsArray(t *testing.T) {
	tmp := t.TempDir()
	st := mustReady(tmp, 8080)
	installFake(t, &fakeLifecycle{listResult: []*state.ServerState{st}})

	res := runCLI(t, "list")
	if res.exitCode != clierrors.Success {
		t.Fatalf("exit = %d", res.exitCode)
	}
	var got map[string]any
	_ = json.Unmarshal([]byte(res.stdout), &got)
	servers, _ := got["servers"].([]any)
	if len(servers) != 1 {
		t.Errorf("expected 1 server; got %v", servers)
	}
}

// ============================================================================
// utility
// ============================================================================

// parseURL returns (host, port) from a httptest URL like "http://127.0.0.1:54123".
func parseURL(t *testing.T, raw string) (string, int) {
	t.Helper()
	const prefix = "http://"
	if len(raw) < len(prefix)+1 || raw[:len(prefix)] != prefix {
		t.Fatalf("unexpected URL: %s", raw)
	}
	rest := raw[len(prefix):]
	host := ""
	portStr := ""
	for i := 0; i < len(rest); i++ {
		if rest[i] == ':' {
			host = rest[:i]
			portStr = rest[i+1:]
			break
		}
	}
	var port int
	for _, c := range portStr {
		if c < '0' || c > '9' {
			break
		}
		port = port*10 + int(c-'0')
	}
	return host, port
}
