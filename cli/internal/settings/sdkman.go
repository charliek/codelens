package settings

import (
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// ParseSDKManRC parses an .sdkmanrc file. Mirrors Python parse_sdkmanrc
// (settings.py:107-130) — notably, it does NOT strip inline `# comments`
// from values: `java=21.0.9-amzn # comment` keeps the comment.
func ParseSDKManRC(path string) (map[string]string, error) {
	out := map[string]string{}
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return out, nil
		}
		return nil, err
	}
	for _, line := range strings.Split(string(data), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		eq := strings.Index(line, "=")
		if eq < 0 {
			continue
		}
		key := strings.TrimSpace(line[:eq])
		value := strings.TrimSpace(line[eq+1:])
		out[key] = value
	}
	return out, nil
}

// FindSDKManJava locates a Java home in ~/.sdkman/candidates/java. Mirrors
// settings.py:148-176 including the prefix-match fallback on the major
// version when an exact match fails. The fallback handles vendor-alias forms
// like "21-tem" or "25-graal" (no patch component) — see issue #35.
func FindSDKManJava(version string) string {
	home, _ := findSDKManJavaWithFallback(version)
	return home
}

// findSDKManJavaWithFallback is the internal variant that also reports
// whether the match required the same-major fallback. Used by callers that
// want to surface the substitution to the user (e.g. a stderr `note:`).
//
// Lookup order:
//  1. Exact directory name match (e.g. "21.0.9-amzn" → that exact dir).
//  2. Same-major fallback: bare integer dir (e.g. major 21 → "21"), then any
//     dir whose name starts with "<major>." (e.g. "21." matches "21.0.9-amzn").
//
// The major is extracted via JavaMajor, which trims SDKMAN vendor aliases
// correctly ("21-tem" → 21, fixing the SplitN bug from earlier releases).
func findSDKManJavaWithFallback(version string) (home string, fellBack bool) {
	userHome, err := os.UserHomeDir()
	if err != nil {
		return "", false
	}
	dir := filepath.Join(userHome, ".sdkman", "candidates", "java")
	if _, err := os.Stat(dir); err != nil {
		return "", false
	}

	exact := filepath.Join(dir, version)
	if fileExists(filepath.Join(exact, "bin", "java")) {
		return exact, false
	}

	major := JavaMajor(version)
	if major == 0 {
		return "", false
	}
	majorStr := strconv.Itoa(major)
	prefix := majorStr + "."

	// Try the bare-major directory first (e.g. ~/.sdkman/candidates/java/21).
	bare := filepath.Join(dir, majorStr)
	if fileExists(filepath.Join(bare, "bin", "java")) {
		return bare, true
	}

	entries, err := os.ReadDir(dir)
	if err != nil {
		return "", false
	}
	for _, e := range entries {
		if !e.IsDir() {
			continue
		}
		if strings.HasPrefix(e.Name(), prefix) {
			cand := filepath.Join(dir, e.Name())
			if fileExists(filepath.Join(cand, "bin", "java")) {
				return cand, true
			}
		}
	}
	return "", false
}

// CodelensJavaVersion reads the codelens repo's own .sdkmanrc to learn
// which Java the server JAR was built against. Returns "" if not available.
// Server JDK selection no longer depends on this (see ResolveServerJavaHome in
// javahome.go, which works without a repo); kept for diagnostics.
func CodelensJavaVersion(s *Settings) string {
	repo, err := FindRepoPath(s)
	if err != nil {
		return ""
	}
	cfg, err := ParseSDKManRC(filepath.Join(repo, ".sdkmanrc"))
	if err != nil {
		return ""
	}
	return cfg["java"]
}
