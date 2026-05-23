package settings

import (
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
)

// DetectProjectJavaVersion mirrors settings.py:198-240. Checks .sdkmanrc,
// .java-version, gradle.properties in that order.
func DetectProjectJavaVersion(projectPath string) string {
	// 1. .sdkmanrc
	cfg, _ := ParseSDKManRC(filepath.Join(projectPath, ".sdkmanrc"))
	if v, ok := cfg["java"]; ok {
		return v
	}
	// 2. .java-version
	if data, err := os.ReadFile(filepath.Join(projectPath, ".java-version")); err == nil {
		v := strings.TrimSpace(string(data))
		if v != "" {
			return v
		}
	}
	// 3. gradle.properties — look for org.gradle.java.home and try to
	//    extract a SDKMAN version from the path.
	data, err := os.ReadFile(filepath.Join(projectPath, "gradle.properties"))
	if err != nil {
		return ""
	}
	re := regexp.MustCompile(`candidates/java/([^/]+)`)
	for _, line := range strings.Split(string(data), "\n") {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, "org.gradle.java.home=") {
			value := strings.TrimSpace(strings.SplitN(line, "=", 2)[1])
			if m := re.FindStringSubmatch(value); m != nil {
				return m[1]
			}
		}
	}
	return ""
}

// ResolveProjectJavaHome mirrors settings.py:243-275, extended to resolve the
// project's JDK from Homebrew as well as SDKMAN (see FindJavaForVersion).
func ResolveProjectJavaHome(projectPath string) string {
	if v := DetectProjectJavaVersion(projectPath); v != "" {
		if home := FindJavaForVersion(v); home != "" {
			return home
		}
	}
	// Explicit org.gradle.java.home path (with ~ expansion only).
	data, err := os.ReadFile(filepath.Join(projectPath, "gradle.properties"))
	if err != nil {
		return ""
	}
	for _, line := range strings.Split(string(data), "\n") {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, "org.gradle.java.home=") {
			path := strings.TrimSpace(strings.SplitN(line, "=", 2)[1])
			path = expandTildeOnly(path)
			if fileExists(filepath.Join(path, "bin", "java")) {
				return path
			}
		}
	}
	return ""
}

// expandTildeOnly mirrors Python Path.expanduser() — only expands ~ at the
// start, never $HOME or ${user.home}.
func expandTildeOnly(p string) string {
	if !strings.HasPrefix(p, "~") {
		return p
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return p
	}
	if p == "~" {
		return home
	}
	if strings.HasPrefix(p, "~/") {
		return filepath.Join(home, p[2:])
	}
	return p
}

// GradleVersion reads gradle/wrapper/gradle-wrapper.properties.
func GradleVersion(projectPath string) string {
	data, err := os.ReadFile(filepath.Join(projectPath, "gradle", "wrapper", "gradle-wrapper.properties"))
	if err != nil {
		return ""
	}
	re := regexp.MustCompile(`gradle-(\d+\.\d+(?:\.\d+)?)`)
	for _, line := range strings.Split(string(data), "\n") {
		if strings.Contains(line, "distributionUrl") {
			if m := re.FindStringSubmatch(line); m != nil {
				return m[1]
			}
		}
	}
	return ""
}

// NeedsOlderJavaForGradle mirrors needs_older_java_for_gradle. Implementation
// of settings.py:303-331 (NOT the diverged docstring): Gradle 8.5+ supports
// Java 21; older versions need older Java.
func NeedsOlderJavaForGradle(projectPath string) bool {
	v := GradleVersion(projectPath)
	if v == "" {
		return false
	}
	parts := strings.Split(v, ".")
	major, _ := strconv.Atoi(parts[0])
	minor := 0
	if len(parts) > 1 {
		minor, _ = strconv.Atoi(parts[1])
	}
	if major > 8 || (major == 8 && minor >= 5) {
		return false
	}
	return true
}
