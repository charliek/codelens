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

func TestMain(m *testing.M) {
	if err := setup(); err != nil {
		fmt.Fprintf(os.Stderr, "parity setup failed: %v\n", err)
		os.Exit(1)
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
	// Tiny settle pause so the next request doesn't race startup.
	time.Sleep(200 * time.Millisecond)
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

	startServer(t)
	t.Cleanup(func() { stopServer(t) })

	for _, c := range allCases {
		c := c // capture
		t.Run(c.Name, func(t *testing.T) {
			args := []string{}
			args = append(args, c.Args...)
			args = append(args, "--project", fixture.projectPath, "--json")

			goRun := runCLI(fixture.goBin, args...)
			if err := goRun.cmd.Run(); err != nil {
				t.Fatalf("go run failed (%s): %v\nstderr=%s", c.Name, err, goRun.stderr.String())
			}
			pyRun := runCLI(fixture.pythonBin, args...)
			if err := pyRun.cmd.Run(); err != nil {
				t.Fatalf("python run failed (%s): %v\nstderr=%s", c.Name, err, pyRun.stderr.String())
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

func truncate(b []byte, max int) string {
	if len(b) <= max {
		return string(b)
	}
	return string(b[:max]) + "... [truncated]"
}
