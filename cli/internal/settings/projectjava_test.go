package settings

import (
	"os"
	"path/filepath"
	"testing"
)

func writeFile(t *testing.T, path, content string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

func TestDetectProjectJavaVersion_SDKManRCFirst(t *testing.T) {
	tmp := t.TempDir()
	writeFile(t, filepath.Join(tmp, ".sdkmanrc"), "java=17.0.10-tem\n")
	writeFile(t, filepath.Join(tmp, ".java-version"), "21\n")
	if got := DetectProjectJavaVersion(tmp); got != "17.0.10-tem" {
		t.Errorf("expected sdkmanrc to win; got %q", got)
	}
}

func TestDetectProjectJavaVersion_JavaVersionFallback(t *testing.T) {
	tmp := t.TempDir()
	writeFile(t, filepath.Join(tmp, ".java-version"), "  11.0.20+8  \n")
	if got := DetectProjectJavaVersion(tmp); got != "11.0.20+8" {
		t.Errorf("got %q", got)
	}
}

func TestDetectProjectJavaVersion_GradlePropertiesSDKManPath(t *testing.T) {
	tmp := t.TempDir()
	writeFile(t, filepath.Join(tmp, "gradle.properties"),
		"org.gradle.java.home=/Users/dev/.sdkman/candidates/java/11.0.20-tem\n")
	if got := DetectProjectJavaVersion(tmp); got != "11.0.20-tem" {
		t.Errorf("got %q", got)
	}
}

func TestDetectProjectJavaVersion_GradlePropertiesJavaVMsPath(t *testing.T) {
	// macOS JavaVMs path — extract bare major so downstream resolvers can
	// find any matching JDK (cask, DMG, manual install).
	tmp := t.TempDir()
	writeFile(t, filepath.Join(tmp, "gradle.properties"),
		"org.gradle.java.home=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home\n")
	if got := DetectProjectJavaVersion(tmp); got != "21" {
		t.Errorf("got %q, want 21", got)
	}
}

func TestDetectProjectJavaVersion_GradlePropertiesLinuxJVMPath(t *testing.T) {
	tmp := t.TempDir()
	writeFile(t, filepath.Join(tmp, "gradle.properties"),
		"org.gradle.java.home=/usr/lib/jvm/temurin-21-jdk-amd64\n")
	if got := DetectProjectJavaVersion(tmp); got != "21" {
		t.Errorf("got %q, want 21", got)
	}
}

func TestDetectProjectGradleJavaHomePath_PresentAndAbsent(t *testing.T) {
	tmp := t.TempDir()
	if got := DetectProjectGradleJavaHomePath(tmp); got != "" {
		t.Errorf("absent: got %q, want empty", got)
	}
	writeFile(t, filepath.Join(tmp, "gradle.properties"),
		"org.gradle.java.home=/nonexistent/jdk21\n")
	if got := DetectProjectGradleJavaHomePath(tmp); got != "/nonexistent/jdk21" {
		t.Errorf("got %q", got)
	}
}

func TestDetectProjectGradleJavaHomePath_TildeExpansion(t *testing.T) {
	home, _ := os.UserHomeDir()
	tmp := t.TempDir()
	writeFile(t, filepath.Join(tmp, "gradle.properties"),
		"org.gradle.java.home=~/.sdkman/candidates/java/21.0.9-amzn\n")
	want := filepath.Join(home, ".sdkman", "candidates", "java", "21.0.9-amzn")
	if got := DetectProjectGradleJavaHomePath(tmp); got != want {
		t.Errorf("got %q, want %q", got, want)
	}
}

// Regression test for the precedence bug caught in PR #37 review: when
// gradle.properties::org.gradle.java.home is the only declaration source
// AND that path exists, codelens must use it directly — not substitute a
// same-major install from SDKMAN. Otherwise, declaring an explicit JDK path
// silently gets overridden by whatever same-major install happens to be
// available, which is surprising and arguably a bug.
func TestResolveProjectJavaHomeWithMatch_ExplicitPathBeatsSameMajor(t *testing.T) {
	tmp := t.TempDir()
	t.Setenv("HOME", tmp)
	t.Setenv("HOMEBREW_PREFIX", t.TempDir())
	t.Setenv("MISE_DATA_DIR", t.TempDir())
	t.Setenv("CODELENS_JAVA_VM_DIRS", t.TempDir())

	// SDKMAN has a major-21 install (potential substitute).
	sdkmanHome := filepath.Join(tmp, ".sdkman", "candidates", "java", "21.0.9-amzn")
	writeFile(t, filepath.Join(sdkmanHome, "bin", "java"), "#!/bin/sh\n")

	// gradle.properties points at a DIFFERENT major-21 home that also exists
	// (simulating an explicit Temurin install referenced by absolute path).
	explicit := filepath.Join(tmp, "my-temurin-21.jdk", "Contents", "Home")
	writeFile(t, filepath.Join(explicit, "bin", "java"), "#!/bin/sh\n")

	proj := filepath.Join(tmp, "project")
	writeFile(t, filepath.Join(proj, "gradle.properties"),
		"org.gradle.java.home="+explicit+"\n")

	got := ResolveProjectJavaHomeWithMatch(proj)
	if got.Home != explicit {
		t.Errorf("home = %q, want %q (explicit path must win over same-major SDKMAN substitute)",
			got.Home, explicit)
	}
	if got.Source != "gradle.properties" {
		t.Errorf("source = %q, want gradle.properties", got.Source)
	}
	if got.FellBack {
		t.Errorf("FellBack should be false when honoring the explicit path")
	}
}

// When .sdkmanrc declares a version, it wins over a parseable
// gradle.properties path. (Existing behavior — make sure the precedence
// fix above doesn't accidentally flip this.)
func TestResolveProjectJavaHomeWithMatch_SDKManBeatsGradlePropertiesWhenBothPresent(t *testing.T) {
	tmp := t.TempDir()
	t.Setenv("HOME", tmp)
	t.Setenv("HOMEBREW_PREFIX", t.TempDir())
	t.Setenv("MISE_DATA_DIR", t.TempDir())
	t.Setenv("CODELENS_JAVA_VM_DIRS", t.TempDir())

	sdkmanHome := filepath.Join(tmp, ".sdkman", "candidates", "java", "21.0.9-amzn")
	writeFile(t, filepath.Join(sdkmanHome, "bin", "java"), "#!/bin/sh\n")
	explicit := filepath.Join(tmp, "other-jdk")
	writeFile(t, filepath.Join(explicit, "bin", "java"), "#!/bin/sh\n")

	proj := filepath.Join(tmp, "project")
	writeFile(t, filepath.Join(proj, ".sdkmanrc"), "java=21.0.9-amzn\n")
	writeFile(t, filepath.Join(proj, "gradle.properties"),
		"org.gradle.java.home="+explicit+"\n")

	got := ResolveProjectJavaHomeWithMatch(proj)
	if got.Source != "SDKMAN" {
		t.Errorf("source = %q, want SDKMAN (sdkmanrc must beat gradle.properties path)", got.Source)
	}
	if got.Home != sdkmanHome {
		t.Errorf("home = %q, want %q", got.Home, sdkmanHome)
	}
}

func TestProjectJavaSource(t *testing.T) {
	t.Run("none", func(t *testing.T) {
		if got := ProjectJavaSource(t.TempDir()); got != "" {
			t.Errorf("got %q", got)
		}
	})
	t.Run("sdkmanrc", func(t *testing.T) {
		tmp := t.TempDir()
		writeFile(t, filepath.Join(tmp, ".sdkmanrc"), "java=21.0.9-amzn\n")
		if got := ProjectJavaSource(tmp); got != ".sdkmanrc" {
			t.Errorf("got %q", got)
		}
	})
	t.Run("java-version", func(t *testing.T) {
		tmp := t.TempDir()
		writeFile(t, filepath.Join(tmp, ".java-version"), "21\n")
		if got := ProjectJavaSource(tmp); got != ".java-version" {
			t.Errorf("got %q", got)
		}
	})
	t.Run("gradle.properties", func(t *testing.T) {
		tmp := t.TempDir()
		writeFile(t, filepath.Join(tmp, "gradle.properties"),
			"org.gradle.java.home=/somewhere\n")
		want := "gradle.properties::org.gradle.java.home"
		if got := ProjectJavaSource(tmp); got != want {
			t.Errorf("got %q, want %q", got, want)
		}
	})
	t.Run("mise", func(t *testing.T) {
		tmp := t.TempDir()
		writeFile(t, filepath.Join(tmp, ".tool-versions"), "java temurin-21.0.9\n")
		if got := ProjectJavaSource(tmp); got != "mise" {
			t.Errorf("got %q", got)
		}
	})
}

func TestGradleVersion(t *testing.T) {
	tmp := t.TempDir()
	writeFile(t,
		filepath.Join(tmp, "gradle", "wrapper", "gradle-wrapper.properties"),
		"distributionUrl=https\\://services.gradle.org/distributions/gradle-7.6.1-bin.zip\n",
	)
	if got := GradleVersion(tmp); got != "7.6.1" {
		t.Errorf("got %q", got)
	}
}

func TestNeedsOlderJavaForGradle(t *testing.T) {
	cases := []struct {
		version string
		want    bool
	}{
		{"7.6.1", true},
		{"8.4", true},
		{"8.5", false},
		{"8.5.0", false},
		{"9.0", false},
	}
	for _, c := range cases {
		tmp := t.TempDir()
		writeFile(t,
			filepath.Join(tmp, "gradle", "wrapper", "gradle-wrapper.properties"),
			"distributionUrl=https\\://services.gradle.org/distributions/gradle-"+c.version+"-bin.zip\n",
		)
		if got := NeedsOlderJavaForGradle(tmp); got != c.want {
			t.Errorf("gradle %s: got %v, want %v", c.version, got, c.want)
		}
	}

	// No wrapper file at all → don't assume.
	if NeedsOlderJavaForGradle(t.TempDir()) {
		t.Errorf("no gradle wrapper should not need older java")
	}
}

func TestExpandTildeOnly(t *testing.T) {
	home, _ := os.UserHomeDir()
	cases := map[string]string{
		"~":           home,
		"~/foo":       filepath.Join(home, "foo"),
		"$HOME/foo":   "$HOME/foo",   // NOT expanded — Python doesn't either.
		"${HOME}/foo": "${HOME}/foo", // NOT expanded.
		"/abs/path":   "/abs/path",
	}
	for in, want := range cases {
		if got := expandTildeOnly(in); got != want {
			t.Errorf("expandTildeOnly(%q) = %q, want %q", in, got, want)
		}
	}
}
