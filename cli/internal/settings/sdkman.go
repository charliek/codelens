package settings

import (
	"os"
	"path/filepath"
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
// version when an exact match fails.
func FindSDKManJava(version string) string {
	home, err := os.UserHomeDir()
	if err != nil {
		return ""
	}
	dir := filepath.Join(home, ".sdkman", "candidates", "java")
	if _, err := os.Stat(dir); err != nil {
		return ""
	}

	exact := filepath.Join(dir, version)
	if fileExists(filepath.Join(exact, "bin", "java")) {
		return exact
	}

	// Prefix-match on major version, e.g. "21" matches "21.0.9-amzn".
	major := strings.SplitN(version, ".", 2)[0] + "."
	entries, err := os.ReadDir(dir)
	if err != nil {
		return ""
	}
	for _, e := range entries {
		if !e.IsDir() {
			continue
		}
		if strings.HasPrefix(e.Name(), major) {
			cand := filepath.Join(dir, e.Name())
			if fileExists(filepath.Join(cand, "bin", "java")) {
				return cand
			}
		}
	}
	return ""
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
