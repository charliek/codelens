package parity

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/google/go-cmp/cmp"
)

// Forces the exec import to be referenced by exitCodeFrom below.
var _ = (*exec.ExitError)(nil)

// fixture is filled by TestMain.
var fixture struct {
	goBin       string // absolute path to compiled Go binary
	pythonBin   string // "codelens" (assumes uv tool install)
	projectPath string // absolute path to test fixture project
	serverJAR   string // absolute path to codelens-server-all.jar
	repoRoot    string // CodeLens repo root
	tmpHome     string // isolated HOME so neither CLI pollutes ~/.cache/codelens
	startedByGo bool
}

// skipMissingPrereqs is set when setup() detects that the JAR or Python CLI
// is unavailable. The single TestParity test reads it and skips with a
// clear message instead of failing the whole test binary. This is what
// makes `go test ./...` pass in CI jobs that don't build the JAR (only
// the dedicated `parity` job does).
var skipMissingPrereqs string

func TestMain(m *testing.M) {
	if err := setup(); err != nil {
		skipMissingPrereqs = err.Error()
	}
	code := m.Run()
	teardown()
	os.Exit(code)
}

func setup() error {
	repo, err := findRepoRoot()
	if err != nil {
		return err
	}
	fixture.repoRoot = repo

	fixture.projectPath = filepath.Join(repo, "test-fixtures", "sample-ratpack-app")
	if _, err := os.Stat(fixture.projectPath); err != nil {
		return fmt.Errorf("test fixture not found at %s", fixture.projectPath)
	}

	fixture.serverJAR = filepath.Join(repo, "server", "app", "build", "libs", "codelens-server-all.jar")
	if _, err := os.Stat(fixture.serverJAR); err != nil {
		return fmt.Errorf("server JAR not built; run `./gradlew :server:app:shadowJar` first")
	}

	// Build the Go binary into a temp location so we test the latest source.
	tmp, err := os.MkdirTemp("", "codelens-parity-*")
	if err != nil {
		return err
	}
	fixture.goBin = filepath.Join(tmp, "codelens-go")
	build := exec.Command("go", "build", "-o", fixture.goBin, "./cmd/codelens")
	build.Dir = filepath.Join(repo, "cli-go")
	build.Env = os.Environ()
	if out, err := build.CombinedOutput(); err != nil {
		return fmt.Errorf("go build failed: %v\n%s", err, out)
	}

	// Python binary: expect the user has already run `uv tool install --editable .`
	// in cli/. We just shell out to `codelens`.
	pythonBin, err := exec.LookPath("codelens")
	if err != nil {
		return fmt.Errorf("python `codelens` not on PATH — run `cd cli && uv tool install --editable .` first")
	}
	fixture.pythonBin = pythonBin

	// Compile the fixture so the server has real project bytecode to scan.
	// The server analyzes already-compiled output (it does not build the
	// target itself), so on a clean checkout (e.g. CI) there are no .class
	// files, projectClassCount is 0, and project-class lookups like
	// `classes show` / `source show` 404. Run only after the JAR + Python
	// checks above so the plain `go test ./...` job — which returns early
	// when the JAR is absent — never triggers a fixture build. Idempotent:
	// Gradle skips the work when the output is already up to date.
	if err := compileFixture(fixture.projectPath); err != nil {
		return err
	}

	// Isolated HOME. The Go CLI hardcodes ~/.cache/codelens; the Python
	// CLI does too. Setting HOME isolates both from any developer state.
	fixture.tmpHome = filepath.Join(tmp, "home")
	if err := os.MkdirAll(fixture.tmpHome, 0o755); err != nil {
		return err
	}

	return nil
}

func teardown() {
	// Best-effort: stop any server we started.
	if fixture.startedByGo && fixture.goBin != "" {
		_ = runCLI(fixture.goBin, "stop", "--project", fixture.projectPath).cmd.Run()
	}
	// Leave the temp dir in place for post-mortem debugging if a test failed.
}

// findRepoRoot walks up from os.Getwd to a directory containing
// gradlew + settings.gradle.kts.
func findRepoRoot() (string, error) {
	cwd, err := os.Getwd()
	if err != nil {
		return "", err
	}
	for i := 0; i < 10; i++ {
		if exists(filepath.Join(cwd, "gradlew")) && exists(filepath.Join(cwd, "settings.gradle.kts")) {
			return cwd, nil
		}
		parent := filepath.Dir(cwd)
		if parent == cwd {
			return "", errors.New("could not find CodeLens repo root")
		}
		cwd = parent
	}
	return "", errors.New("walked too many levels looking for repo root")
}

func exists(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}

// compileFixture builds the sample project's main source set via its own
// Gradle wrapper so the server has bytecode to scan. Without compiled output
// the fixture has zero project classes and the success-path cases (e.g.
// classes_show, source_show) fail because the server returns 404. Inherits
// the ambient environment so it picks up JAVA_HOME from the CI job (or the
// developer's active JDK locally).
func compileFixture(projectPath string) error {
	gradlew := filepath.Join(projectPath, "gradlew")
	if !exists(gradlew) {
		return fmt.Errorf("fixture gradlew not found at %s", gradlew)
	}
	cmd := exec.Command(gradlew, "classes", "--quiet")
	cmd.Dir = projectPath
	cmd.Env = os.Environ()
	if out, err := cmd.CombinedOutput(); err != nil {
		return fmt.Errorf("failed to compile test fixture %s: %v\n%s", projectPath, err, out)
	}
	return nil
}

// cliRun encapsulates a CLI invocation and its captured output.
type cliRun struct {
	cmd    *exec.Cmd
	stdout bytes.Buffer
	stderr bytes.Buffer
}

func runCLI(binary string, args ...string) *cliRun {
	cmd := exec.Command(binary, args...)
	cmd.Env = append(os.Environ(),
		"HOME="+fixture.tmpHome,
		"CODELENS_SERVER_JAR="+fixture.serverJAR,
	)
	r := &cliRun{cmd: cmd}
	cmd.Stdout = &r.stdout
	cmd.Stderr = &r.stderr
	return r
}

// startServer brings up a single server we'll share across all parity cases.
func startServer(t *testing.T) {
	t.Helper()
	r := runCLI(fixture.goBin, "start", "--project", fixture.projectPath, "--mode", "jar", "--timeout", "240")
	if err := r.cmd.Run(); err != nil {
		t.Fatalf("go start failed: %v\nstderr=%s", err, r.stderr.String())
	}
	fixture.startedByGo = true
	// Wait for the scan to complete (status transitions from LOADING to READY).
	// The HTTP server comes up immediately but bytecode scanning runs in the
	// background. Without this wait, fast-running cases can hit 503 ScanNotReady.
	waitForScanReady(t)
}

// waitForScanReady polls `classes stats` until it succeeds (scan complete)
// or 120s elapse. The sample fixture is small, so this typically completes
// in <30s even with a cold Gradle cache.
func waitForScanReady(t *testing.T) {
	t.Helper()
	deadline := time.Now().Add(120 * time.Second)
	for time.Now().Before(deadline) {
		r := runCLI(fixture.goBin, "classes", "stats", "--project", fixture.projectPath, "--json")
		if err := r.cmd.Run(); err == nil {
			// Success means scan is complete.
			return
		}
		time.Sleep(2 * time.Second)
	}
	t.Fatalf("scan did not complete within 120s")
}

// stopServer is registered as a Cleanup on the top-level subtest so it
// only runs once.
func stopServer(t *testing.T) {
	t.Helper()
	r := runCLI(fixture.goBin, "stop", "--project", fixture.projectPath)
	_ = r.cmd.Run() // best-effort
	fixture.startedByGo = false
}

// TestParity exercises every Case in the manifest against a single shared
// server. It's gated behind `go test -run TestParity` so the default `go
// test ./...` invocation in unit-test contexts doesn't spawn a JVM.
func TestParity(t *testing.T) {
	if testing.Short() {
		t.Skip("parity tests require the server JAR and a live JVM; skipped in -short mode")
	}
	if skipMissingPrereqs != "" {
		t.Skipf("parity prerequisites not available: %s", skipMissingPrereqs)
	}

	startServer(t)
	t.Cleanup(func() { stopServer(t) })

	for _, c := range allCases {
		c := c // capture
		t.Run(c.Name, func(t *testing.T) {
			args := []string{}
			for _, a := range c.Args {
				// Allow manifest entries to reference paths inside the
				// fixture project via {{PROJECT}} — substituted at run time
				// so the manifest stays machine-agnostic.
				args = append(args, strings.ReplaceAll(a, "{{PROJECT}}", fixture.projectPath))
			}
			args = append(args, "--project", fixture.projectPath, "--json")

			goRun := runCLI(fixture.goBin, args...)
			goErr := goRun.cmd.Run()
			pyRun := runCLI(fixture.pythonBin, args...)
			pyErr := pyRun.cmd.Run()

			// Verify both CLIs returned the expected exit code (0 by
			// default, non-zero for cases like `lint check` against a
			// project with violations).
			goExit := exitCodeFrom(goErr)
			pyExit := exitCodeFrom(pyErr)
			if goExit != c.ExpectExitCode || pyExit != c.ExpectExitCode {
				t.Fatalf("exit-code mismatch (%s): go=%d py=%d expected=%d\ngo stderr:%s\npy stderr:%s",
					c.Name, goExit, pyExit, c.ExpectExitCode,
					goRun.stderr.String(), pyRun.stderr.String())
			}

			goOut := goRun.stdout.Bytes()
			pyOut := pyRun.stdout.Bytes()

			// Compare. For JSON output we structurally compare after
			// normalizing mutable fields. For DOT (non-JSON), trim both
			// sides of trailing whitespace and compare verbatim.
			if looksLikeJSON(goOut) && looksLikeJSON(pyOut) {
				diffJSON(t, c, goOut, pyOut)
			} else {
				diffRaw(t, c, goOut, pyOut)
			}
		})
	}
}

func looksLikeJSON(b []byte) bool {
	trim := bytes.TrimSpace(b)
	return len(trim) > 0 && (trim[0] == '{' || trim[0] == '[')
}

func diffJSON(t *testing.T, c Case, goOut, pyOut []byte) {
	t.Helper()
	goNorm := normalizeJSON(goOut, c.BlankPaths)
	pyNorm := normalizeJSON(pyOut, c.BlankPaths)

	var goObj, pyObj any
	_ = json.Unmarshal(goNorm, &goObj)
	_ = json.Unmarshal(pyNorm, &pyObj)

	if diff := cmp.Diff(pyObj, goObj); diff != "" {
		t.Errorf("parity mismatch (%s):\n--- python (-)  +++ go (+)\n%s", c.Name, diff)
		t.Logf("go stdout (first 1KB):\n%s", truncate(goOut, 1024))
		t.Logf("py stdout (first 1KB):\n%s", truncate(pyOut, 1024))
	}
}

func diffRaw(t *testing.T, c Case, goOut, pyOut []byte) {
	t.Helper()
	goStr := strings.TrimRight(string(goOut), "\n\t ")
	pyStr := strings.TrimRight(string(pyOut), "\n\t ")
	if goStr != pyStr {
		t.Errorf("raw output mismatch (%s):\n--- python ---\n%s\n--- go ---\n%s",
			c.Name, truncate([]byte(pyStr), 1024), truncate([]byte(goStr), 1024))
	}
}

// exitCodeFrom turns an *exec.Cmd error into a numeric exit code.
// Returns 0 if err is nil, the actual exit code if the process exited
// cleanly with non-zero, or -1 for non-exit failures (couldn't spawn, etc.).
func exitCodeFrom(err error) int {
	if err == nil {
		return 0
	}
	if exitErr, ok := err.(*exec.ExitError); ok {
		return exitErr.ExitCode()
	}
	return -1
}

func truncate(b []byte, max int) string {
	if len(b) <= max {
		return string(b)
	}
	return string(b[:max]) + "... [truncated]"
}
