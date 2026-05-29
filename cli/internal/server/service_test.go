package server

import (
	"bytes"
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

// isolatedHome points HOME at a temp dir (isolating SDKMAN + the default mise
// data dir) and neutralizes any system Homebrew prefix so resolution only sees
// what the test creates.
func isolatedHome(t *testing.T) string {
	t.Helper()
	tmp := t.TempDir()
	t.Setenv("HOME", tmp)
	t.Setenv("HOMEBREW_PREFIX", t.TempDir())
	t.Setenv("MISE_DATA_DIR", t.TempDir())
	return tmp
}

func newJARService(t *testing.T, fakeJAR string) *Service {
	t.Helper()
	return &Service{
		Settings: &settings.Settings{
			Host: "127.0.0.1", IdleTimeout: "30m",
			PortRangeStart: 8080, PortRangeEnd: 8080,
			ServerJAROverride: fakeJAR,
		},
	}
}

// A declared + installed project JDK is resolved and passed as
// --project-java-home, regardless of the project's (modern) Gradle version.
func TestResolveProjectJava_DeclaredAndInstalled(t *testing.T) {
	tmp := isolatedHome(t)

	// Fake SDKMAN layout: ~/.sdkman/candidates/java/11.0.20-tem/bin/java
	javaHome := filepath.Join(tmp, ".sdkman", "candidates", "java", "11.0.20-tem")
	fakeFile(t, filepath.Join(javaHome, "bin", "java"), "#!/bin/sh\n")

	// Modern Gradle (8.6) + .sdkmanrc requesting Java 11 — the declaration must
	// still be honored (it's no longer gated on the Gradle version).
	proj := filepath.Join(tmp, "project")
	fakeFile(t, filepath.Join(proj, "gradle", "wrapper", "gradle-wrapper.properties"),
		"distributionUrl=https\\://services.gradle.org/distributions/gradle-8.6-bin.zip\n")
	fakeFile(t, filepath.Join(proj, ".sdkmanrc"), "java=11.0.20-tem\n")

	fakeJAR := filepath.Join(tmp, "codelens-server-all.jar")
	fakeFile(t, fakeJAR, "fake")
	svc := newJARService(t, fakeJAR)

	home, err := svc.resolveProjectJava(proj)
	if err != nil {
		t.Fatalf("resolveProjectJava: %v", err)
	}
	if filepath.Base(home) != "11.0.20-tem" {
		t.Fatalf("expected the SDKMAN 11 home; got %q", home)
	}

	opts := StartOptions{ProjectPath: proj, ServerJAR: fakeJAR, ProjectJavaHome: home}
	cmd, err := svc.buildCommand(opts, state.ServerModeJAR, 8080)
	if err != nil {
		t.Fatal(err)
	}
	args := strings.Join(cmd.Args, " ")
	if !strings.Contains(args, "--project-java-home") || !strings.Contains(args, "11.0.20-tem") {
		t.Errorf("expected --project-java-home with the resolved home; got: %s", args)
	}
}

// A declared-but-not-installed JDK is a hard error naming the version.
func TestResolveProjectJava_DeclaredNotInstalled_Errors(t *testing.T) {
	tmp := isolatedHome(t)
	proj := filepath.Join(tmp, "project")
	fakeFile(t, filepath.Join(proj, ".sdkmanrc"), "java=11.0.99-nope\n")

	svc := &Service{Settings: &settings.Settings{}}
	if _, err := svc.resolveProjectJava(proj); err == nil {
		t.Fatal("expected an error for a declared-but-uninstalled JDK")
	} else if !strings.Contains(err.Error(), "11.0.99-nope") {
		t.Errorf("error should name the declared version; got: %v", err)
	}
}

// No declaration anywhere is a hard error telling the user how to declare one.
func TestResolveProjectJava_Undeclared_Errors(t *testing.T) {
	tmp := isolatedHome(t)
	proj := filepath.Join(tmp, "project")
	fakeFile(t, filepath.Join(proj, "gradle", "wrapper", "gradle-wrapper.properties"),
		"distributionUrl=https\\://services.gradle.org/distributions/gradle-8.6-bin.zip\n")

	svc := &Service{Settings: &settings.Settings{}}
	if _, err := svc.resolveProjectJava(proj); err == nil {
		t.Fatal("expected an error when no JDK is declared")
	} else if !strings.Contains(err.Error(), "no JDK declared") {
		t.Errorf("error should explain no JDK was declared; got: %v", err)
	}
}

// A mise .tool-versions declaration resolves to a mise install.
func TestResolveProjectJava_MiseDeclaration(t *testing.T) {
	tmp := isolatedHome(t)
	miseData := os.Getenv("MISE_DATA_DIR")
	javaHome := filepath.Join(miseData, "installs", "java", "temurin-17.0.10")
	fakeFile(t, filepath.Join(javaHome, "bin", "java"), "#!/bin/sh\n")

	proj := filepath.Join(tmp, "project")
	fakeFile(t, filepath.Join(proj, ".tool-versions"), "java temurin-17.0.10\n")

	svc := &Service{Settings: &settings.Settings{}}
	home, err := svc.resolveProjectJava(proj)
	if err != nil {
		t.Fatalf("resolveProjectJava: %v", err)
	}
	if filepath.Base(home) != "temurin-17.0.10" {
		t.Fatalf("expected the mise install home; got %q", home)
	}
}

// Gradle mode also passes --project-java-home (previously it was dropped).
func TestBuildCommand_GradleModePassesProjectJava(t *testing.T) {
	tmp := t.TempDir()
	repo := filepath.Join(tmp, "repo")
	fakeFile(t, filepath.Join(repo, "gradlew"), "#!/bin/sh\n")
	fakeFile(t, filepath.Join(repo, "settings.gradle.kts"), "")

	svc := &Service{Settings: &settings.Settings{IdleTimeout: "30m", RepoPath: repo}}
	opts := StartOptions{ProjectPath: filepath.Join(tmp, "proj"), ProjectJavaHome: "/fake/jdk21"}
	cmd, err := svc.buildCommand(opts, state.ServerModeGradle, 8080)
	if err != nil {
		t.Fatal(err)
	}
	args := strings.Join(cmd.Args, " ")
	if !strings.Contains(args, `--project-java-home "/fake/jdk21"`) {
		t.Errorf("gradle mode should pass quoted --project-java-home; got: %s", args)
	}
}

func TestWriteWarnings(t *testing.T) {
	var buf bytes.Buffer
	writeWarnings(&buf, []string{"project may not be compiled", "second advisory"})
	got := buf.String()
	want := "warning: project may not be compiled\nwarning: second advisory\n"
	if got != want {
		t.Errorf("writeWarnings output mismatch:\n got %q\nwant %q", got, want)
	}
}

func TestWriteWarnings_EmptyIsSilent(t *testing.T) {
	var buf bytes.Buffer
	writeWarnings(&buf, nil)
	if buf.Len() != 0 {
		t.Errorf("no warnings should produce no output; got %q", buf.String())
	}
}
