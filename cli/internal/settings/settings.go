// Package settings centralizes environment-driven configuration and the
// Java / Gradle / SDKMAN detection heuristics required to launch the
// CodeLens server. All logic mirrors cli/src/codelens_cli/settings.py.
package settings

import (
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// Settings collects everything resolved from the environment + defaults.
type Settings struct {
	ServerMode        string // "auto" | "gradle" | "jar"
	IdleTimeout       string // e.g. "30m"
	PortRangeStart    int
	PortRangeEnd      int
	Host              string
	JavaHome          string   // CODELENS_JAVA_HOME
	JavaOpts          []string // CODELENS_JAVA_OPTS (whitespace-split)
	RepoPath          string   // CODELENS_REPO_PATH
	ServerJAROverride string   // CODELENS_SERVER_JAR
}

// Load reads environment variables, applies defaults, and returns a struct.
// Mirrors AppSettings in cli/src/codelens_cli/settings.py.
func Load() *Settings {
	s := &Settings{
		ServerMode:  getenv("CODELENS_SERVER__MODE", "auto"),
		IdleTimeout: getenv("CODELENS_SERVER__IDLE_TIMEOUT", "30m"),
		// Default to the IANA dynamic/private range, above Linux's default
		// ephemeral range (32768-60999), so auto-allocated ports rarely collide
		// with other programs. The actual port is discovered by the CLI via the
		// server's ready-line and state file, so the specific number is opaque.
		PortRangeStart:    getenvInt("CODELENS_SERVER__PORT_RANGE__START", 61000),
		PortRangeEnd:      getenvInt("CODELENS_SERVER__PORT_RANGE__END", 65535),
		Host:              getenv("CODELENS_SERVER__HOST", "127.0.0.1"),
		JavaHome:          os.Getenv("CODELENS_JAVA_HOME"),
		RepoPath:          os.Getenv("CODELENS_REPO_PATH"),
		ServerJAROverride: os.Getenv("CODELENS_SERVER_JAR"),
	}
	if opts := os.Getenv("CODELENS_JAVA_OPTS"); opts != "" {
		s.JavaOpts = strings.Fields(opts)
	}
	return s
}

func getenv(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func getenvInt(key string, def int) int {
	if v := os.Getenv(key); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return def
}

// CacheDir returns ~/.cache/codelens. Hardcoded on all platforms to match
// Python (settings.py:69-73) and avoid Go's os.UserCacheDir() which would
// pick ~/Library/Caches on macOS, breaking interop.
func CacheDir() (string, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	dir := filepath.Join(home, ".cache", "codelens")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", err
	}
	return dir, nil
}
