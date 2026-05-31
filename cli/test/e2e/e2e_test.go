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

// fixture is filled by TestMain. Paths are absolute.
var fixture struct {
	goBin         string // compiled Go binary
	serverJAR     string // codelens-server-all.jar
	repoRoot      string // CodeLens repo root
	tmpHome       string // isolated HOME so the CLI doesn't pollute ~/.cache/codelens
	ratpackPath   string // test-fixtures/sample-ratpack-app
	springPath    string // test-fixtures/sample-spring-boot-app
	micronautPath string // test-fixtures/sample-micronaut-app
}

// startedProjects tracks which fixture projects we started a server for, so
// teardown can stop them best-effort.
var startedProjects = map[string]bool{}

// skipMissingPrereqs is set when setup() detects that the JAR is unavailable.
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

	fixture.ratpackPath = filepath.Join(repo, "test-fixtures", "sample-ratpack-app")
	fixture.springPath = filepath.Join(repo, "test-fixtures", "sample-spring-boot-app")
	fixture.micronautPath = filepath.Join(repo, "test-fixtures", "sample-micronaut-app")
	for _, p := range []string{fixture.ratpackPath, fixture.springPath, fixture.micronautPath} {
		if _, err := os.Stat(p); err != nil {
			return fmt.Errorf("test fixture not found at %s", p)
		}
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
	// Embed version.txt (gitignored, regenerated each build) before building,
	// matching `make build`, so the suite is self-contained even on a clean
	// checkout where the embed file doesn't exist yet.
	gen := exec.Command("go", "generate", "./...")
	gen.Dir = filepath.Join(repo, "cli")
	gen.Env = os.Environ()
	if out, err := gen.CombinedOutput(); err != nil {
		return fmt.Errorf("go generate failed: %v\n%s", err, out)
	}
	build := exec.Command("go", "build", "-o", fixture.goBin, "./cmd/codelens")
	build.Dir = filepath.Join(repo, "cli")
	build.Env = os.Environ()
	if out, err := build.CombinedOutput(); err != nil {
		return fmt.Errorf("go build failed: %v\n%s", err, out)
	}

	// Compile the fixtures so the server has real bytecode to scan. The server
	// analyzes already-compiled output (it does not build the target itself),
	// so on a clean checkout there are no .class files. Run only after the JAR
	// check above so the plain `go test ./...` job — which returns early when
	// the JAR is absent — never triggers a fixture build. Idempotent.
	for _, p := range []string{fixture.ratpackPath, fixture.springPath, fixture.micronautPath} {
		if err := compileFixture(p); err != nil {
			return err
		}
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
	for proj := range startedProjects {
		if fixture.goBin != "" {
			_ = runCLI(fixture.goBin, "stop", "--project", proj).cmd.Run()
		}
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

// compileFixture builds a sample project's main source set via its own Gradle
// wrapper so the server has bytecode to scan. Inherits the ambient environment
// so it picks up JAVA_HOME from the CI job (or the developer's active JDK).
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

// startServer brings up a server for projectPath, shared across that fixture's cases.
func startServer(t *testing.T, projectPath string) {
	t.Helper()
	// codelens requires the target project's JDK to be declared/resolvable.
	// On CI the JDK comes from actions/setup-java (a plain JAVA_HOME, not
	// SDKMAN/Homebrew/mise), so pass it explicitly; locally we fall back to the
	// fixture's .sdkmanrc.
	startArgs := []string{"start", "--project", projectPath, "--mode", "jar", "--timeout", "240"}
	if jh := os.Getenv("JAVA_HOME"); jh != "" {
		startArgs = append(startArgs, "--project-java", jh)
	}
	r := runCLI(fixture.goBin, startArgs...)
	if err := r.cmd.Run(); err != nil {
		t.Fatalf("start failed for %s: %v\nstderr=%s", projectPath, err, r.stderr.String())
	}
	startedProjects[projectPath] = true
	waitForScanReady(t, projectPath)
}

// waitForScanReady polls `classes stats` until it succeeds (scan complete)
// or 120s elapse.
func waitForScanReady(t *testing.T, projectPath string) {
	t.Helper()
	deadline := time.Now().Add(120 * time.Second)
	for time.Now().Before(deadline) {
		r := runCLI(fixture.goBin, "classes", "stats", "--project", projectPath, "--json")
		if err := r.cmd.Run(); err == nil {
			return
		}
		time.Sleep(2 * time.Second)
	}
	t.Fatalf("scan did not complete within 120s for %s", projectPath)
}

func stopServer(t *testing.T, projectPath string) {
	t.Helper()
	r := runCLI(fixture.goBin, "stop", "--project", projectPath)
	_ = r.cmd.Run() // best-effort
	delete(startedProjects, projectPath)
}

// pathReplacements maps the run-specific absolute paths to stable placeholders
// so golden fixtures stay machine- and CI-agnostic. projectPath maps to
// {{PROJECT}} for the fixture under test.
func pathReplacements(projectPath string) []pathReplacement {
	return []pathReplacement{
		{from: fixture.serverJAR, to: "{{JAR}}"},
		{from: projectPath, to: "{{PROJECT}}"},
		{from: fixture.repoRoot, to: "{{REPO}}"},
		{from: fixture.tmpHome, to: "{{HOME}}"},
	}
}

// TestE2E exercises the sample-ratpack-app cases against a live server and
// diffs the CLI's --json output against committed golden fixtures. Gated behind
// `go test -run TestE2E`. Regenerate goldens with:
//
//	UPDATE_GOLDEN=1 go test -run TestE2E ./test/e2e/...
func TestE2E(t *testing.T) {
	guardE2E(t)
	runSuite(t, fixture.ratpackPath, allCases, filepath.Join("testdata", "golden"))
}

// TestE2ESpring exercises the richer sample-spring-boot-app fixture, proving the
// general primitives work on a second framework. Goldens live under
// testdata/golden/spring/.
func TestE2ESpring(t *testing.T) {
	guardE2E(t)
	runSuite(t, fixture.springPath, springCases, filepath.Join("testdata", "golden", "spring"))
}

// TestE2EMicronaut exercises the self-contained sample-micronaut-app fixture
// (Micronaut + Flyway + Hikari, Kotlin), proving the general primitives work on
// a third framework with zero framework-specific tool code — entirely in-repo,
// no external project. Goldens live under testdata/golden/micronaut/.
func TestE2EMicronaut(t *testing.T) {
	guardE2E(t)
	runSuite(t, fixture.micronautPath, micronautCases, filepath.Join("testdata", "golden", "micronaut"))
}

// TestE2ETableSmoke exercises the human-readable (--table) output path against
// a live ratpack server. Unlike the golden suites (which always pass --json),
// this proves the table renderers decode real server responses: the substring
// assertions check decoded *data*, so a wrong struct tag would surface as JSON
// (caught by looksLikeJSON) or as missing data (caught by the substring check).
func TestE2ETableSmoke(t *testing.T) {
	guardE2E(t)
	proj := fixture.ratpackPath
	startServer(t, proj)
	t.Cleanup(func() { stopServer(t, proj) })

	cases := []struct {
		name   string
		args   []string
		expect string // a token derived from decoded data, not just static text
	}{
		{"classes_list", []string{"classes", "list"}, "BlockingHandler"},
		{"classes_show", []string{"classes", "show", "sample.handlers.BlockingHandler"}, "ratpack.handling.Handler"},
		{"classes_stats", []string{"classes", "stats"}, "Project Classes:"},
		{"methods_search", []string{"methods", "search", "--name", "handle"}, "handle"},
		{"calls", []string{"calls", "sample.api.UsersApi", "--method", "execute"}, "Chain"},
		{"xref", []string{"xref", "sample.handlers.UserService"}, "AsyncHandler"},
		// @Inject sits on the handler constructors, so the new annotations renderer
		// must decode and show the CONSTRUCTOR target (proves the unified shape).
		{"annotations_usages", []string{"annotations", "usages", "javax.inject.Inject", "--scope", "all"}, "CONSTRUCTOR"},
		{"deps_foundation", []string{"deps", "foundation"}, "UserService"},
		{"deps_graph", []string{"deps", "graph"}, "nodes"},
		{"source_show", []string{"source", "show", "sample.handlers.SimpleHandler"}, "class SimpleHandler"},
		{"project", []string{"project"}, "sample-ratpack-app"},
	}
	for _, c := range cases {
		c := c
		t.Run(c.name, func(t *testing.T) {
			args := append(append([]string{}, c.args...), "--project", proj, "--table")
			run := runCLI(fixture.goBin, args...)
			if err := run.cmd.Run(); err != nil {
				t.Fatalf("%s failed: %v\nstderr=%s", c.name, err, run.stderr.String())
			}
			if looksLikeJSON(run.stdout.Bytes()) {
				t.Fatalf("%s: --table produced JSON, not a table:\n%s", c.name, truncate(run.stdout.Bytes(), 512))
			}
			if !strings.Contains(run.stdout.String(), c.expect) {
				t.Errorf("%s: table output missing decoded token %q:\n%s", c.name, c.expect, truncate(run.stdout.Bytes(), 512))
			}
		})
	}
}

func guardE2E(t *testing.T) {
	t.Helper()
	if testing.Short() {
		t.Skip("e2e tests require the server JAR and a live JVM; skipped in -short mode")
	}
	if skipMissingPrereqs != "" {
		t.Skipf("e2e prerequisites not available: %s", skipMissingPrereqs)
	}
}

// runSuite starts a server for projectPath, runs every case, and diffs against
// goldens in goldenDir. UPDATE_GOLDEN=1 rewrites them.
func runSuite(t *testing.T, projectPath string, cases []Case, goldenDir string) {
	update := os.Getenv("UPDATE_GOLDEN") == "1"
	if update {
		if err := os.MkdirAll(goldenDir, 0o755); err != nil {
			t.Fatalf("mkdir golden dir: %v", err)
		}
	}

	startServer(t, projectPath)
	t.Cleanup(func() { stopServer(t, projectPath) })

	repls := pathReplacements(projectPath)

	for _, c := range cases {
		c := c // capture
		t.Run(c.Name, func(t *testing.T) {
			args := []string{}
			for _, a := range c.Args {
				// Allow manifest entries to reference paths inside the fixture
				// project via {{PROJECT}} — substituted at run time.
				args = append(args, strings.ReplaceAll(a, "{{PROJECT}}", projectPath))
			}
			args = append(args, "--project", projectPath, "--json")

			run := runCLI(fixture.goBin, args...)
			err := run.cmd.Run()

			exit := exitCodeFrom(err)
			if exit != c.ExpectExitCode {
				t.Fatalf("exit-code mismatch (%s): got=%d expected=%d\nstderr:%s",
					c.Name, exit, c.ExpectExitCode, run.stderr.String())
			}

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

// compareGolden checks `got` against the committed golden file.
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
