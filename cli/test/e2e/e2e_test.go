package e2e

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

// fixture is filled by TestMain.
var fixture struct {
	goBin       string // absolute path to compiled Go binary
	projectPath string // absolute path to test fixture project
	serverJAR   string // absolute path to codelens-server-all.jar
	repoRoot    string // CodeLens repo root
	tmpHome     string // isolated HOME so the CLI doesn't pollute ~/.cache/codelens
	startedByGo bool
}

// skipMissingPrereqs is set when setup() detects that the JAR is unavailable.
// The single TestE2E test reads it and skips with a clear message instead of
// failing the whole test binary. This is what makes `go test ./...` pass in
// the unit-test CI job that doesn't build the JAR (only the dedicated `e2e`
// job does).
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
	tmp, err := os.MkdirTemp("", "codelens-e2e-*")
	if err != nil {
		return err
	}
	fixture.goBin = filepath.Join(tmp, "codelens-go")
	build := exec.Command("go", "build", "-o", fixture.goBin, "./cmd/codelens")
	build.Dir = filepath.Join(repo, "cli")
	build.Env = os.Environ()
	if out, err := build.CombinedOutput(); err != nil {
		return fmt.Errorf("go build failed: %v\n%s", err, out)
	}

	// Compile the fixture so the server has real project bytecode to scan.
	// The server analyzes already-compiled output (it does not build the
	// target itself), so on a clean checkout (e.g. CI) there are no .class
	// files, projectClassCount is 0, and project-class lookups like
	// `classes show` / `source show` 404. Run only after the JAR check above
	// so the plain `go test ./...` job — which returns early when the JAR is
	// absent — never triggers a fixture build. Idempotent: Gradle skips the
	// work when the output is already up to date.
	if err := compileFixture(fixture.projectPath); err != nil {
		return err
	}

	// Isolated HOME. The Go CLI hardcodes ~/.cache/codelens; setting HOME
	// isolates it from any developer state.
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

// startServer brings up a single server we'll share across all cases.
func startServer(t *testing.T) {
	t.Helper()
	r := runCLI(fixture.goBin, "start", "--project", fixture.projectPath, "--mode", "jar", "--timeout", "240")
	if err := r.cmd.Run(); err != nil {
		t.Fatalf("start failed: %v\nstderr=%s", err, r.stderr.String())
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

// pathReplacements maps the run-specific absolute paths to stable placeholders
// so golden fixtures stay machine- and CI-agnostic.
func pathReplacements() []pathReplacement {
	return []pathReplacement{
		{from: fixture.serverJAR, to: "{{JAR}}"},
		{from: fixture.projectPath, to: "{{PROJECT}}"},
		{from: fixture.repoRoot, to: "{{REPO}}"},
		{from: fixture.tmpHome, to: "{{HOME}}"},
	}
}

// TestE2E exercises every Case in the manifest against a single shared server
// and diffs the CLI's --json output against committed golden fixtures. It's
// gated behind `go test -run TestE2E` so the default `go test ./...`
// invocation in unit-test contexts doesn't spawn a JVM. Regenerate the
// goldens after an intentional output change with:
//
//	UPDATE_GOLDEN=1 go test -run TestE2E ./test/e2e/...
func TestE2E(t *testing.T) {
	if testing.Short() {
		t.Skip("e2e tests require the server JAR and a live JVM; skipped in -short mode")
	}
	if skipMissingPrereqs != "" {
		t.Skipf("e2e prerequisites not available: %s", skipMissingPrereqs)
	}

	update := os.Getenv("UPDATE_GOLDEN") == "1"
	goldenDir := filepath.Join("testdata", "golden")
	if update {
		if err := os.MkdirAll(goldenDir, 0o755); err != nil {
			t.Fatalf("mkdir golden dir: %v", err)
		}
	}

	startServer(t)
	t.Cleanup(func() { stopServer(t) })

	repls := pathReplacements()

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

			run := runCLI(fixture.goBin, args...)
			err := run.cmd.Run()

			// Verify the CLI returned the expected exit code (0 by default,
			// non-zero for cases like `lint check` against a project with
			// violations).
			exit := exitCodeFrom(err)
			if exit != c.ExpectExitCode {
				t.Fatalf("exit-code mismatch (%s): got=%d expected=%d\nstderr:%s",
					c.Name, exit, c.ExpectExitCode, run.stderr.String())
			}

			// Normalize mutable fields, then template machine-specific paths
			// so the artifact is portable. For DOT (non-JSON) output
			// normalizeJSON is a no-op and only templating applies.
			got := normalizeJSON(run.stdout.Bytes(), c.BlankPaths)
			got = templatizePaths(got, repls)

			goldenPath := filepath.Join(goldenDir, c.Name+".golden")
			if update {
				if err := os.WriteFile(goldenPath, append(got, '\n'), 0o644); err != nil {
					t.Fatalf("%s: write golden: %v", c.Name, err)
				}
				return
			}
			compareGolden(t, c, got, goldenPath)
		})
	}
}

// compareGolden checks `got` against the committed golden file. JSON cases
// are compared structurally (after the deterministic re-marshal in
// normalizeJSON); DOT/raw cases are compared verbatim after trimming trailing
// whitespace.
func compareGolden(t *testing.T, c Case, got []byte, goldenPath string) {
	t.Helper()
	want, err := os.ReadFile(goldenPath)
	if err != nil {
		t.Fatalf("%s: cannot read golden %s — regenerate with UPDATE_GOLDEN=1: %v", c.Name, goldenPath, err)
	}

	if looksLikeJSON(got) {
		var gotObj, wantObj any
		if err := json.Unmarshal(got, &gotObj); err != nil {
			t.Fatalf("%s: CLI output is not valid JSON: %v", c.Name, err)
		}
		if err := json.Unmarshal(want, &wantObj); err != nil {
			t.Fatalf("%s: golden is not valid JSON — regenerate with UPDATE_GOLDEN=1: %v", c.Name, err)
		}
		if diff := cmp.Diff(wantObj, gotObj); diff != "" {
			t.Errorf("%s: output drifted from golden (regenerate with UPDATE_GOLDEN=1):\n--- golden (-)  +++ got (+)\n%s", c.Name, diff)
			t.Logf("got (first 1KB):\n%s", truncate(got, 1024))
		}
		return
	}

	gotStr := strings.TrimRight(string(got), "\n\t ")
	wantStr := strings.TrimRight(string(want), "\n\t ")
	if gotStr != wantStr {
		t.Errorf("%s: raw output drifted from golden (regenerate with UPDATE_GOLDEN=1):\n--- golden ---\n%s\n--- got ---\n%s",
			c.Name, truncate([]byte(wantStr), 1024), truncate([]byte(gotStr), 1024))
	}
}

func looksLikeJSON(b []byte) bool {
	trim := bytes.TrimSpace(b)
	return len(trim) > 0 && (trim[0] == '{' || trim[0] == '[')
}

// exitCodeFrom turns an *exec.Cmd error into a numeric exit code.
// Returns 0 if err is nil, the actual exit code if the process exited
// cleanly with non-zero, or -1 for non-exit failures (couldn't spawn, etc.).
func exitCodeFrom(err error) int {
	if err == nil {
		return 0
	}
	var exitErr *exec.ExitError
	if errors.As(err, &exitErr) {
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
