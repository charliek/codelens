package server

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/charliek/codelens/cli/internal/settings"
	"github.com/charliek/codelens/cli/internal/state"
)

// fakeFile drops a file with the given contents at path, creating parents
// as needed.
func fakeFile(t *testing.T, path, content string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

// TestBuildCommand_AutoDetectProjectJava asserts that when the target
// project uses an older Gradle and declares a Java version that exists in
// SDKMAN, the resulting exec.Cmd includes --project-java-home pointing at
// the resolved JDK. Mirrors the bug-quirk locked test environment from
// settings_test/sdkman_test.go.
func TestBuildCommand_AutoDetectProjectJava(t *testing.T) {
	tmp := t.TempDir()
	t.Setenv("HOME", tmp)

	// Fake SDKMAN layout: ~/.sdkman/candidates/java/11.0.20-tem/bin/java
	javaHome := filepath.Join(tmp, ".sdkman", "candidates", "java", "11.0.20-tem")
	fakeFile(t, filepath.Join(javaHome, "bin", "java"), "#!/bin/sh\n")

	// Target project: Gradle 7.6 wrapper + .sdkmanrc requesting Java 11.
	proj := filepath.Join(tmp, "old-gradle-project")
	fakeFile(t, filepath.Join(proj, "gradle", "wrapper", "gradle-wrapper.properties"),
		"distributionUrl=https\\://services.gradle.org/distributions/gradle-7.6.1-bin.zip\n")
	fakeFile(t, filepath.Join(proj, ".sdkmanrc"), "java=11.0.20-tem\n")

	// A bogus JAR path so JAR mode runs the build-command logic without
	// requiring the real shadow JAR.
	fakeJAR := filepath.Join(tmp, "codelens-server-all.jar")
	fakeFile(t, fakeJAR, "fake")

	svc := &Service{
		Settings: &settings.Settings{
			Host: "127.0.0.1", IdleTimeout: "30m",
			PortRangeStart: 8080, PortRangeEnd: 8080,
			ServerJAROverride: fakeJAR,
		},
	}

	// Resolve project Java the same way Start() would.
	opts := StartOptions{ProjectPath: proj, ServerJAR: fakeJAR}
	if opts.ProjectJavaHome == "" {
		opts.ProjectJavaHome = svc.autoResolveProjectJava(opts.ProjectPath)
	}
	if opts.ProjectJavaHome == "" {
		t.Fatal("autoResolveProjectJava returned empty; expected the SDKMAN java home")
	}

	cmd, err := svc.buildCommand(opts, state.ServerModeJAR, 8080)
	if err != nil {
		t.Fatal(err)
	}

	args := strings.Join(cmd.Args, " ")
	if !strings.Contains(args, "--project-java-home") {
		t.Errorf("expected --project-java-home in args; got: %s", args)
	}
	if !strings.Contains(args, "11.0.20-tem") {
		t.Errorf("expected the SDKMAN-resolved Java home in args; got: %s", args)
	}
}

// When the target project's Gradle is modern (8.5+), don't bother detecting.
func TestBuildCommand_ModernGradleSkipsAutoDetect(t *testing.T) {
	tmp := t.TempDir()
	t.Setenv("HOME", tmp)

	proj := filepath.Join(tmp, "modern-project")
	fakeFile(t, filepath.Join(proj, "gradle", "wrapper", "gradle-wrapper.properties"),
		"distributionUrl=https\\://services.gradle.org/distributions/gradle-8.6-bin.zip\n")
	fakeFile(t, filepath.Join(proj, ".sdkmanrc"), "java=11.0.20-tem\n")

	svc := &Service{Settings: &settings.Settings{}}
	if got := svc.autoResolveProjectJava(proj); got != "" {
		t.Errorf("modern Gradle should skip auto-detect; got %q", got)
	}
}

// When detection wants a JDK that isn't in SDKMAN, return "" so we still
// try `java` from PATH but emit a hint on stderr.
func TestBuildCommand_VersionNotInstalled_ReturnsEmpty(t *testing.T) {
	tmp := t.TempDir()
	t.Setenv("HOME", tmp)

	proj := filepath.Join(tmp, "old-gradle-no-sdk")
	fakeFile(t, filepath.Join(proj, "gradle", "wrapper", "gradle-wrapper.properties"),
		"distributionUrl=https\\://services.gradle.org/distributions/gradle-7.6.1-bin.zip\n")
	fakeFile(t, filepath.Join(proj, ".sdkmanrc"), "java=11.0.99-nope\n")

	svc := &Service{Settings: &settings.Settings{}}
	got := svc.autoResolveProjectJava(proj)
	if got != "" {
		t.Errorf("expected empty (no SDKMAN match); got %q", got)
	}
}
