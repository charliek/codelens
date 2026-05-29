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
