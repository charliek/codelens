package settings

import (
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
)

// DetectProjectJavaVersion checks .sdkmanrc, .java-version, gradle.properties,
// and mise (.mise.toml / mise.toml / .tool-versions) in that order. Returns ""
// when the project declares no JDK.
//
// For gradle.properties::org.gradle.java.home, the extracted version is:
//  1. The SDKMAN candidate name when the path matches `.../candidates/java/<v>`.
//  2. Otherwise, the major version derived from the path basename, when
//     parseable as a JavaVMs / Homebrew / jvm name (e.g.
//     `/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home` → "21").
//  3. Otherwise, "" (fall through to mise detection).
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
	// 3. gradle.properties — look for org.gradle.java.home.
	if data, err := os.ReadFile(filepath.Join(projectPath, "gradle.properties")); err == nil {
		re := regexp.MustCompile(`candidates/java/([^/]+)`)
		for _, line := range strings.Split(string(data), "\n") {
			line = strings.TrimSpace(line)
			if strings.HasPrefix(line, "org.gradle.java.home=") {
				value := strings.TrimSpace(strings.SplitN(line, "=", 2)[1])
				if m := re.FindStringSubmatch(value); m != nil {
					return m[1]
				}
				// Non-SDKMAN absolute path — derive a major from the path
				// so downstream resolution still has something to work with.
				if maj := JavaMajorFromHome(expandTildeOnly(value)); maj > 0 {
					return strconv.Itoa(maj)
				}
			}
		}
	}
	// 4. mise (.mise.toml / mise.toml / .tool-versions)
	return MiseProjectJavaVersion(projectPath)
}

// DetectProjectGradleJavaHomePath returns the raw, ~-expanded path declared
// by `org.gradle.java.home=<path>` in gradle.properties, regardless of
// whether `bin/java` exists at that path. Returns "" when no such
// declaration is present. Used by service.go to detect the "declared path
// missing" case and produce a pointed error instead of the misleading
// "no JDK declared".
func DetectProjectGradleJavaHomePath(projectPath string) string {
	data, err := os.ReadFile(filepath.Join(projectPath, "gradle.properties"))
	if err != nil {
		return ""
	}
	for _, line := range strings.Split(string(data), "\n") {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, "org.gradle.java.home=") {
			path := strings.TrimSpace(strings.SplitN(line, "=", 2)[1])
			return expandTildeOnly(path)
		}
	}
	return ""
}

// ProjectJavaSource returns a short tag identifying which mechanism the
// project uses to declare its JDK. Used in error messages so the user knows
// which file to edit. Returns "" when no declaration is present.
func ProjectJavaSource(projectPath string) string {
	if cfg, _ := ParseSDKManRC(filepath.Join(projectPath, ".sdkmanrc")); cfg["java"] != "" {
		return ".sdkmanrc"
	}
	if data, err := os.ReadFile(filepath.Join(projectPath, ".java-version")); err == nil {
		if strings.TrimSpace(string(data)) != "" {
			return ".java-version"
		}
	}
	if DetectProjectGradleJavaHomePath(projectPath) != "" {
		return "gradle.properties::org.gradle.java.home"
	}
	if MiseProjectJavaVersion(projectPath) != "" {
		return "mise"
	}
	return ""
}

// ResolveProjectJavaHome resolves the target project's JDK home. Tries the
// declared version through SDKMAN → Homebrew → JavaVMs → mise (with same-major
// fallback at each source), then the explicit `org.gradle.java.home` path.
// Returns "" when nothing resolves. Callers that want attribution
// (source + fallback flag for error messages and stderr notes) should use
// ResolveProjectJavaHomeWithMatch instead.
func ResolveProjectJavaHome(projectPath string) string {
	return ResolveProjectJavaHomeWithMatch(projectPath).Home
}

// ProjectJavaResolution describes the outcome of project-JDK resolution
// with full attribution for diagnostics.
type ProjectJavaResolution struct {
	Home      string // resolved JAVA_HOME; "" when nothing matched
	Requested string // version declared by the project (DetectProjectJavaVersion)
	Matched   string // basename of the install dir that matched (may differ from Requested)
	Source    string // "SDKMAN" | "Homebrew" | "JavaVMs" | "mise" | "gradle.properties" | ""
	FellBack  bool   // true when Matched != Requested (same-major substitution)
}

// ResolveProjectJavaHomeWithMatch is the source-of-truth resolver returning
// rich attribution so callers can surface a one-line stderr note when a
// same-major fallback fires (e.g. requested "21-tem", matched "21.0.9-amzn").
func ResolveProjectJavaHomeWithMatch(projectPath string) ProjectJavaResolution {
	requested := DetectProjectJavaVersion(projectPath)
	if requested != "" {
		if home, info := findJavaForVersionWithSource(requested); home != "" {
			return ProjectJavaResolution{
				Home: home, Requested: requested,
				Matched: info.matchedName, Source: info.source,
				FellBack: info.fellBack,
			}
		}
	}
	// Explicit org.gradle.java.home path (with ~ expansion only) as a last
	// resort. Useful when the declared version didn't resolve but the user
	// pointed Gradle directly at a JDK home that exists.
	if path := DetectProjectGradleJavaHomePath(projectPath); path != "" {
		if fileExists(filepath.Join(path, "bin", "java")) {
			return ProjectJavaResolution{
				Home: path, Requested: requested,
				Matched: filepath.Base(path), Source: "gradle.properties",
				FellBack: false,
			}
		}
	}
	return ProjectJavaResolution{Requested: requested}
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
