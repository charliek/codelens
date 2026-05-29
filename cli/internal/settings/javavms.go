package settings

import (
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
)

// javaVMVendorPrefixes are the well-known vendor prefixes used in
// JavaVMs/jvm install directory names. They're stripped before parsing the
// version number out of e.g. "temurin-21.0.5+11.jdk" → "21.0.5+11" or
// "amazon-corretto-21.jdk" → "21". Order matters: longer-prefix-first so
// "graalvm-ce-java" wins over "graalvm-".
var javaVMVendorPrefixes = []string{
	"amazon-corretto-",
	"graalvm-community-java",
	"graalvm-ce-java",
	"graalvm-",
	"liberica-",
	"microsoft-",
	"openjdk-",
	"oracle-",
	"semeru-",
	"temurin-",
	"zulu-",
	"jdk-",
	"java-",
}

// JavaMajorFromVMName extracts a Java major version from a JavaVMs / jvm
// install directory basename (e.g. "temurin-21.jdk", "amazon-corretto-21.jdk",
// "jdk-21.0.5.jdk", "graalvm-ce-java21-22.3.1", "temurin-21-jdk-amd64").
// Returns 0 when no major can be parsed.
func JavaMajorFromVMName(name string) int {
	name = strings.TrimSuffix(name, ".jdk")
	for _, p := range javaVMVendorPrefixes {
		if strings.HasPrefix(name, p) {
			if m := JavaMajor(strings.TrimPrefix(name, p)); m > 0 {
				return m
			}
		}
	}
	return JavaMajor(name)
}

// javaVMDirs returns the directories to scan for JavaVMs/jvm installs.
//
//   - CODELENS_JAVA_VM_DIRS (comma-separated, leading/trailing whitespace
//     trimmed per entry) overrides the default. Test/diagnostic use only.
//   - On macOS: the system and per-user JavaVirtualMachines dirs.
//   - On Linux: /usr/lib/jvm.
//   - Other GOOS: empty list (no auto-discovery beyond the env override).
func javaVMDirs() []string {
	if env := os.Getenv("CODELENS_JAVA_VM_DIRS"); env != "" {
		var out []string
		for _, x := range strings.Split(env, ",") {
			if t := strings.TrimSpace(x); t != "" {
				out = append(out, t)
			}
		}
		return out
	}
	switch runtime.GOOS {
	case "darwin":
		out := []string{"/Library/Java/JavaVirtualMachines"}
		if home, err := os.UserHomeDir(); err == nil {
			out = append(out, filepath.Join(home, "Library", "Java", "JavaVirtualMachines"))
		}
		return out
	case "linux":
		return []string{"/usr/lib/jvm"}
	}
	return nil
}

// javaVMResolveHome returns the JAVA_HOME for an install dir under one of
// javaVMDirs(): on macOS the path is `<dir>/Contents/Home`; on Linux it's
// `<dir>` directly. Returns "" if neither location has bin/java.
func javaVMResolveHome(dir string) string {
	if h := filepath.Join(dir, "Contents", "Home"); fileExists(filepath.Join(h, "bin", "java")) {
		return h
	}
	if fileExists(filepath.Join(dir, "bin", "java")) {
		return dir
	}
	return ""
}

// javaVMInstalledInfos lists installed JavaVMs/jvm JDKs as JavaInstallInfo
// entries for server-JVM discovery (ResolveServerJavaHome) and error
// messages (InstalledJavaSummaries). Sorted for deterministic order; deduped
// by resolved real path so Linux `default-java → temurin-21-jdk-amd64`
// symlinks don't produce double entries. Broken symlinks are skipped silently.
//
// When a symlink and its real target are both listed in the same dir, the
// real target's basename is used for vendor parsing (the symlink name is
// often generic like "default-java" and yields no version info).
func javaVMInstalledInfos() []JavaInstallInfo {
	seen := map[string]bool{}
	var out []JavaInstallInfo
	for _, dir := range javaVMDirs() {
		entries, err := os.ReadDir(dir)
		if err != nil {
			continue
		}
		sort.Slice(entries, func(i, j int) bool {
			return entries[i].Name() < entries[j].Name()
		})
		for _, e := range entries {
			full := filepath.Join(dir, e.Name())
			real, err := filepath.EvalSymlinks(full)
			if err != nil {
				continue
			}
			if seen[real] {
				continue
			}
			seen[real] = true
			home := javaVMResolveHome(full)
			if home == "" {
				continue
			}
			// Prefer the real path's basename for major parsing — the
			// symlink name (e.g. "default-java") often has no vendor/version.
			name := filepath.Base(real)
			m := JavaMajorFromVMName(name)
			if m == 0 {
				// Fall back to the entry name if the real basename was unhelpful
				// (e.g. a Homebrew "openjdk" keg symlinked into JavaVMs).
				name = e.Name()
				m = JavaMajorFromVMName(name)
				if m == 0 {
					continue
				}
			}
			out = append(out, JavaInstallInfo{
				Home: home, Major: m, Source: "JavaVMs", Name: name,
			})
		}
	}
	return out
}

// FindJavaVMJava resolves a requested Java version to a JavaVMs / jvm install
// home. Tries exact directory-name match first (e.g. request "temurin-21.jdk"
// matches that exact dir), then falls back to any install with the same major
// version. When multiple installs share the major, picks the one whose name
// sorts highest lexically — for like-vendor entries this approximates
// "highest patch version". Returns "" if nothing matches.
func FindJavaVMJava(version string) string {
	if version == "" {
		return ""
	}
	major := JavaMajor(version)
	var bestHome, bestName string
	for _, dir := range javaVMDirs() {
		entries, err := os.ReadDir(dir)
		if err != nil {
			continue
		}
		sort.Slice(entries, func(i, j int) bool {
			return entries[i].Name() < entries[j].Name()
		})
		for _, e := range entries {
			full := filepath.Join(dir, e.Name())
			home := javaVMResolveHome(full)
			if home == "" {
				continue
			}
			if e.Name() == version {
				return home
			}
			if major > 0 && JavaMajorFromVMName(e.Name()) == major {
				if e.Name() > bestName {
					bestHome, bestName = home, e.Name()
				}
			}
		}
	}
	return bestHome
}
