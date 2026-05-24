package settings

import (
	"os"
	"path/filepath"
	"strings"
)

// MiseProjectJavaVersion reads a mise-managed Java version declared by the
// project, checking `.mise.toml`, `mise.toml`, and `.tool-versions` in that
// order. Returns "" when mise doesn't declare a java tool. Best-effort.
func MiseProjectJavaVersion(projectPath string) string {
	for _, name := range []string{".mise.toml", "mise.toml"} {
		if v := parseMiseTomlJava(filepath.Join(projectPath, name)); v != "" {
			return v
		}
	}
	return parseToolVersionsJava(filepath.Join(projectPath, ".tool-versions"))
}

// parseMiseTomlJava extracts the `java` tool version from a mise TOML config.
// Handles the common forms within the [tools] table:
//
//	java = "21.0.9-amzn"
//	java = ['21']
//	java = { version = "21" }
//
// Best-effort line scanner (no full TOML parse).
func parseMiseTomlJava(path string) string {
	data, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	inTools := false
	for _, line := range strings.Split(string(data), "\n") {
		t := strings.TrimSpace(line)
		if t == "" || strings.HasPrefix(t, "#") {
			continue
		}
		if strings.HasPrefix(t, "[") {
			inTools = t == "[tools]"
			continue
		}
		if !inTools {
			continue
		}
		key, val, ok := cutKey(t)
		if !ok || key != "java" {
			continue
		}
		return firstVersionToken(val)
	}
	return ""
}

// parseToolVersionsJava extracts the first java version from a .tool-versions
// line like `java temurin-21.0.9` (asdf/mise format).
func parseToolVersionsJava(path string) string {
	data, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	for _, line := range strings.Split(string(data), "\n") {
		t := strings.TrimSpace(line)
		if t == "" || strings.HasPrefix(t, "#") {
			continue
		}
		fields := strings.Fields(t)
		if len(fields) >= 2 && fields[0] == "java" {
			return fields[1]
		}
	}
	return ""
}

// cutKey splits `key = value` (TOML-ish). Returns trimmed key and value.
func cutKey(line string) (key, val string, ok bool) {
	eq := strings.Index(line, "=")
	if eq < 0 {
		return "", "", false
	}
	return strings.TrimSpace(line[:eq]), strings.TrimSpace(line[eq+1:]), true
}

// firstVersionToken pulls a version string out of a TOML value: the first
// quoted string if present (covers "x", ['x'], {version="x"}), else a bare
// token.
func firstVersionToken(val string) string {
	if i := strings.IndexAny(val, "\"'"); i >= 0 {
		q := val[i]
		rest := val[i+1:]
		if j := strings.IndexByte(rest, q); j >= 0 {
			return rest[:j]
		}
	}
	// Bare token (e.g. java = 21) — trim trailing comment/brackets.
	val = strings.TrimRight(val, "}],")
	val = strings.TrimSpace(val)
	if i := strings.IndexByte(val, '#'); i >= 0 {
		val = strings.TrimSpace(val[:i])
	}
	return val
}

// FindMiseJava resolves a requested Java version to a mise install home under
// ${MISE_DATA_DIR:-~/.local/share/mise}/installs/java. Tries an exact dir match,
// then a prefix match (e.g. "21" or "temurin-21" -> "temurin-21.0.9"). Checks
// both <dir>/bin/java and the macOS <dir>/Contents/Home layout. Returns "" if
// not found.
func FindMiseJava(version string) string {
	dir := miseInstallsJavaDir()
	if dir == "" {
		return ""
	}
	if home := miseResolveHome(filepath.Join(dir, version)); home != "" {
		return home
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		return ""
	}
	major := miseMajor(version)
	for _, e := range entries {
		if !e.IsDir() {
			continue
		}
		name := e.Name()
		if strings.HasPrefix(name, version) || strings.HasPrefix(name, version+".") ||
			(major > 0 && miseMajor(name) == major) {
			if home := miseResolveHome(filepath.Join(dir, name)); home != "" {
				return home
			}
		}
	}
	return ""
}

// miseInstalledJavaHomes lists installed mise JDKs as javaInstall entries for
// the server-JVM discovery (ResolveServerJavaHome).
func miseInstalledJavaHomes() []javaInstall {
	dir := miseInstallsJavaDir()
	if dir == "" {
		return nil
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil
	}
	var out []javaInstall
	for _, e := range entries {
		if !e.IsDir() {
			continue
		}
		m := miseMajor(e.Name())
		if m == 0 {
			continue
		}
		if home := miseResolveHome(filepath.Join(dir, e.Name())); home != "" {
			out = append(out, javaInstall{home: home, major: m})
		}
	}
	return out
}

// miseResolveHome returns the Java home for a mise install dir: the dir itself
// if it has bin/java, else the macOS Contents/Home subdir. "" if neither.
func miseResolveHome(dir string) string {
	if fileExists(filepath.Join(dir, "bin", "java")) {
		return dir
	}
	if h := filepath.Join(dir, "Contents", "Home"); fileExists(filepath.Join(h, "bin", "java")) {
		return h
	}
	return ""
}

// miseInstallsJavaDir returns the mise java installs directory, honoring
// MISE_DATA_DIR and XDG_DATA_HOME, defaulting to ~/.local/share/mise.
func miseInstallsJavaDir() string {
	base := os.Getenv("MISE_DATA_DIR")
	if base == "" {
		if xdg := os.Getenv("XDG_DATA_HOME"); xdg != "" {
			base = filepath.Join(xdg, "mise")
		}
	}
	if base == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			return ""
		}
		base = filepath.Join(home, ".local", "share", "mise")
	}
	return filepath.Join(base, "installs", "java")
}

// miseMajor extracts a Java major version from a mise version/dir name that may
// carry a distro prefix, e.g. "temurin-21.0.9" -> 21, "21.0.9" -> 21. Returns 0
// when no version is present.
func miseMajor(name string) int {
	for i := 0; i < len(name); i++ {
		if name[i] >= '0' && name[i] <= '9' {
			return JavaMajor(name[i:])
		}
	}
	return 0
}
