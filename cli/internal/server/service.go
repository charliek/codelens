package server

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/charliek/codelens/cli/internal/settings"
	"github.com/charliek/codelens/cli/internal/state"
)

// Service is the lifecycle controller. Holds a Repository for state and a
// Settings struct for env config.
type Service struct {
	Settings *settings.Settings
	Repo     *state.Repository
	// HTTPClient is used for the graceful shutdown POST. Override in tests.
	HTTPClient *http.Client
}

// New returns a Service wired up with the default cache dir + 5s HTTP client.
func New(s *settings.Settings, repo *state.Repository) *Service {
	return &Service{
		Settings:   s,
		Repo:       repo,
		HTTPClient: &http.Client{Timeout: 5 * time.Second},
	}
}

// Find returns the running server for a project, or nil if there's no
// state file or the recorded PID is no longer alive (cleans up in that case).
func (s *Service) Find(projectPath string) (*state.ServerState, error) {
	st, err := s.Repo.Find(projectPath)
	if err != nil || st == nil {
		return nil, err
	}
	if !state.IsProcessRunning(st.PID) {
		_ = s.Repo.Delete(projectPath)
		return nil, nil
	}
	return st, nil
}

// StartOptions controls a Start invocation.
type StartOptions struct {
	ProjectPath     string
	Mode            state.ServerMode // empty = auto
	Port            int              // 0 = allocate
	Timeout         time.Duration    // 0 = 180s
	ProjectJavaHome string           // empty = auto-detect
	ServerJAR       string           // empty = settings.FindServerJAR
}

// Start spawns the server, blocks until CODELENS_READY, and returns the
// updated state.
//
// If a server is already running and READY for this project, returns it
// unchanged.
func (s *Service) Start(ctx context.Context, opts StartOptions) (*state.ServerState, error) {
	if opts.Timeout == 0 {
		opts.Timeout = 180 * time.Second
	}
	if existing, _ := s.Find(opts.ProjectPath); existing != nil && existing.Status == state.StatusReady {
		return existing, nil
	}

	mode := s.resolveMode(opts.Mode, opts.ServerJAR)
	port := opts.Port
	if port == 0 {
		p, err := AllocatePort(s.Settings.PortRangeStart, s.Settings.PortRangeEnd)
		if err != nil {
			return nil, err
		}
		port = p
	}

	// The target project's Gradle daemon must run on the project's declared
	// JDK. Resolve it unless an explicit --project-java was passed; a missing or
	// unresolvable declaration is a hard error (we never guess a JDK).
	if opts.ProjectJavaHome == "" {
		home, err := s.resolveProjectJava(opts.ProjectPath)
		if err != nil {
			return nil, err
		}
		opts.ProjectJavaHome = home
	}

	cmd, err := s.buildCommand(opts, mode, port)
	if err != nil {
		return nil, err
	}

	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	logPath := s.Repo.LogFile(opts.ProjectPath)
	logFile, err := os.Create(logPath)
	if err != nil {
		_ = stdout.Close()
		return nil, fmt.Errorf("open log file %s: %w", logPath, err)
	}
	defer func() { _ = logFile.Close() }()
	cmd.Stderr = logFile

	cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true}

	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("spawn server: %w", err)
	}

	if _, err := s.Repo.Save(opts.ProjectPath, cmd.Process.Pid, port, s.Settings.Host, mode, s.Settings.IdleTimeout); err != nil {
		_ = killGroup(cmd.Process.Pid)
		return nil, err
	}

	waitCtx, cancel := context.WithTimeout(ctx, opts.Timeout)
	defer cancel()
	info, err := WaitForReady(waitCtx, stdout)
	if err != nil {
		_ = killGroup(cmd.Process.Pid)
		_ = s.Repo.Delete(opts.ProjectPath)
		_, _ = io.Copy(io.Discard, stdout) // drain
		return nil, augmentReadyError(err, logPath)
	}

	// Reload, update status, persist the server-reported port.
	st, err := s.Repo.Find(opts.ProjectPath)
	if err != nil || st == nil {
		return nil, err
	}
	st.Port = info.Port
	st.Status = state.StatusReady
	if err := s.writeState(opts.ProjectPath, st); err != nil {
		return nil, err
	}
	return st, nil
}

func (s *Service) writeState(projectPath string, st *state.ServerState) error {
	// We don't expose Repository.write; round-trip via Save+UpdateStatus.
	// Simpler: write the file directly via a JSON-marshal helper.
	if err := s.Repo.Delete(projectPath); err != nil {
		return err
	}
	// Restore by calling Save then UpdateStatus(state.Status).
	if _, err := s.Repo.Save(projectPath, st.PID, st.Port, st.Host, st.ServerMode, st.IdleTimeout); err != nil {
		return err
	}
	if err := s.Repo.UpdateStatus(projectPath, st.Status); err != nil {
		return err
	}
	return nil
}

// Stop tries graceful shutdown via POST /admin/shutdown, falls back to
// SIGTERM/SIGKILL on the process group. Returns true if a server was
// stopped, false if none was running. Mirrors server_service.py:426-461.
func (s *Service) Stop(projectPath string, force bool) (bool, error) {
	st, err := s.Find(projectPath)
	if err != nil || st == nil {
		return false, err
	}
	pid := st.PID

	if !force {
		url := fmt.Sprintf("http://%s:%d/admin/shutdown", st.Host, st.Port)
		req, _ := http.NewRequest(http.MethodPost, url, nil)
		resp, err := s.HTTPClient.Do(req)
		if err == nil {
			_ = resp.Body.Close()
			// Poll up to 5s for the process to exit.
			for i := 0; i < 50; i++ {
				if !state.IsProcessRunning(pid) {
					break
				}
				time.Sleep(100 * time.Millisecond)
			}
		}
	}

	if state.IsProcessRunning(pid) {
		_ = syscall.Kill(-pid, syscall.SIGTERM)
		time.Sleep(500 * time.Millisecond)
		if state.IsProcessRunning(pid) {
			_ = syscall.Kill(-pid, syscall.SIGKILL)
		}
	}

	if err := s.Repo.Delete(projectPath); err != nil {
		return true, err
	}
	return true, nil
}

// ListAll returns every running server (cleans up stale state files).
func (s *Service) ListAll() ([]*state.ServerState, error) {
	return s.Repo.ListAll()
}

// resolveMode implements the locked `--mode auto` algorithm: if any JAR
// candidate resolves, use JAR; otherwise GRADLE.
func (s *Service) resolveMode(explicit state.ServerMode, overrideJAR string) state.ServerMode {
	if explicit != "" && explicit != state.ServerModeAuto {
		return explicit
	}
	if s.Settings.ServerMode == "gradle" {
		return state.ServerModeGradle
	}
	if s.Settings.ServerMode == "jar" {
		return state.ServerModeJAR
	}
	// auto: prefer JAR if a candidate exists.
	if settings.FindServerJAR(s.Settings, overrideJAR) != "" {
		return state.ServerModeJAR
	}
	return state.ServerModeGradle
}

func (s *Service) buildCommand(opts StartOptions, mode state.ServerMode, port int) (*exec.Cmd, error) {
	idleTimeout := s.Settings.IdleTimeout

	switch mode {
	case state.ServerModeGradle:
		repo, err := settings.FindRepoPath(s.Settings)
		if err != nil {
			return nil, err
		}
		gradlew := filepath.Join(repo, "gradlew")
		serverArgs := fmt.Sprintf("--project %s --port %d --idle-timeout %s", opts.ProjectPath, port, idleTimeout)
		if opts.ProjectJavaHome != "" {
			serverArgs += fmt.Sprintf(" --project-java-home %s", opts.ProjectJavaHome)
		}
		args := []string{
			":server:app:run",
			"--args=" + serverArgs,
		}
		cmd := exec.Command(gradlew, args...)
		cmd.Dir = repo
		return cmd, nil

	case state.ServerModeJAR:
		jar := opts.ServerJAR
		if jar == "" {
			jar = settings.FindServerJAR(s.Settings, "")
		}
		if jar == "" {
			return nil, errors.New("server JAR not found (set CODELENS_SERVER_JAR, build with ./gradlew :server:app:shadowJar, or pass --server-jar)")
		}
		java, serverMajor := s.resolveServerJava()
		s.warnIfTargetNewerThanServer(opts.ProjectPath, serverMajor)
		args := append(append([]string{}, s.Settings.JavaOpts...),
			"-jar", jar,
			"--project", opts.ProjectPath,
			"--port", strconv.Itoa(port),
			"--idle-timeout", idleTimeout,
		)
		if opts.ProjectJavaHome != "" {
			args = append(args, "--project-java-home", opts.ProjectJavaHome)
		}
		return exec.Command(java, args...), nil
	}
	return nil, fmt.Errorf("unknown server mode: %s", mode)
}

// resolveProjectJava resolves the JDK home for the target project's Gradle
// daemon. The project must declare a JDK (.sdkmanrc / .java-version /
// gradle.properties / mise) so its Gradle runs on a compatible JVM regardless
// of the (possibly newer) server JVM. codelens never guesses: a missing or
// unresolvable declaration is a hard error that aborts startup. Pass
// --project-java to bypass.
func (s *Service) resolveProjectJava(projectPath string) (string, error) {
	if home := settings.ResolveProjectJavaHome(projectPath); home != "" {
		return home, nil
	}
	if v := settings.DetectProjectJavaVersion(projectPath); v != "" {
		brew := "`brew install openjdk@<major>`"
		if m := settings.JavaMajor(v); m > 0 {
			brew = fmt.Sprintf("`brew install openjdk@%d`", m)
		}
		return "", fmt.Errorf(
			"project %s declares Java %s but it isn't installed; install it "+
				"(`sdk install java %s`, %s, or `mise install java@%s`) or pass --project-java",
			projectPath, v, v, brew, v)
	}
	hint := ""
	if g := settings.GradleVersion(projectPath); g != "" {
		hint = fmt.Sprintf(" (Gradle %s)", g)
	}
	return "", fmt.Errorf(
		"no JDK declared for project %s%s; declare one via .sdkmanrc, .java-version, "+
			"gradle.properties (org.gradle.java.home), or mise (.mise.toml / .tool-versions), "+
			"or pass --project-java",
		projectPath, hint)
}

// resolveServerJava resolves the JVM that runs the server JAR and, when known,
// its major version. Order: CODELENS_JAVA_HOME → SDKMAN/Homebrew (highest JDK in
// [ServerJavaFloor, ServerJavaCeiling]) → JAVA_HOME → bare "java" on PATH. The
// returned major is 0 when it can't be determined (e.g. an opaque JAVA_HOME or
// the PATH fallback), in which case the target-version warning is skipped.
func (s *Service) resolveServerJava() (javaBin string, major int) {
	if s.Settings.JavaHome != "" {
		bin := filepath.Join(s.Settings.JavaHome, "bin", "java")
		if fileExists(bin) {
			return bin, settings.JavaMajorFromHome(s.Settings.JavaHome)
		}
	}
	if home, m := settings.ResolveServerJavaHome(s.Settings); home != "" {
		bin := filepath.Join(home, "bin", "java")
		if fileExists(bin) {
			return bin, m
		}
	}
	if env := os.Getenv("JAVA_HOME"); env != "" {
		bin := filepath.Join(env, "bin", "java")
		if fileExists(bin) {
			return bin, settings.JavaMajorFromHome(env)
		}
	}
	return "java", 0
}

// warnIfTargetNewerThanServer prints a hint when the target project's bytecode
// is newer than the JVM that will run the server. A server JVM must be >= the
// target's class-file version to analyze it. serverMajor == 0 means unknown, so
// we stay quiet rather than warn spuriously.
func (s *Service) warnIfTargetNewerThanServer(projectPath string, serverMajor int) {
	if serverMajor == 0 {
		return
	}
	target := settings.JavaMajor(settings.DetectProjectJavaVersion(projectPath))
	if target == 0 || target <= serverMajor {
		return
	}
	if target > settings.ServerJavaCeiling {
		// The target is newer than the highest JDK codelens runs the server on,
		// so there's no in-range JDK that can satisfy it.
		fmt.Fprintf(os.Stderr,
			"warning: server is running Java %d but %s targets Java %d, which is newer "+
				"than the highest JDK codelens runs the server on (Java %d); analysis may "+
				"be incomplete and this ceiling may need to be raised.\n",
			serverMajor, projectPath, target, settings.ServerJavaCeiling)
		return
	}
	fmt.Fprintf(os.Stderr,
		"warning: server is running Java %d but %s targets Java %d; "+
			"install a JDK >= %d (<= %d) via `sdk install java %d...` or "+
			"`brew install openjdk@%d` so codelens can analyze it.\n",
		serverMajor, projectPath, target, target, settings.ServerJavaCeiling, target, target)
}

func fileExists(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}

// killGroup signals the entire process group rooted at pid. Required to
// reap Gradle's child processes too.
func killGroup(pid int) error {
	if pid <= 0 {
		return nil
	}
	if err := syscall.Kill(-pid, syscall.SIGTERM); err == nil {
		return nil
	}
	return syscall.Kill(pid, syscall.SIGTERM)
}

// augmentReadyError wraps a ready-parse failure with hints from the log file.
func augmentReadyError(err error, logPath string) error {
	if err == nil {
		return nil
	}
	data, readErr := os.ReadFile(logPath)
	if readErr != nil {
		return err
	}
	content := string(data)
	if strings.Contains(content, "Unsupported class file major version") {
		return fmt.Errorf("%w (gradle/java incompatibility in %s: the project's gradle version cannot run with java 21 — install an older java with `sdk install java 11.0.20-tem` or pass --project-java)", err, logPath)
	}
	if strings.Contains(content, "UnsupportedClassVersionError") {
		return fmt.Errorf("%w (the codelens server JAR needs a newer java; set CODELENS_JAVA_HOME to a java 21+ install or use --mode gradle — see log %s)", err, logPath)
	}
	return err
}
