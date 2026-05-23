package settings

import (
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// ServerJavaFloor and ServerJavaCeiling bound the JDK the CLI will use to run
// the server JAR.
//
//   - Floor = the server's build target (the minimum JVM that can launch it;
//     the Kotlin modules compile to JVM 21).
//   - Ceiling = the newest JDK the server stack (Kotlin/Ktor/Netty, the Gradle
//     Tooling API, and ClassGraph) is verified on.
//
// The server JVM must be >= the target project's bytecode version, so we run
// the server on the newest installed JDK in range to maximize the set of
// target projects it can analyze without rebuilding.
const (
	ServerJavaFloor   = 21
	ServerJavaCeiling = 25
)

// ResolveServerJavaHome returns the home of the highest installed JDK whose
// major version is within [ServerJavaFloor, ServerJavaCeiling], searching both
// SDKMAN and Homebrew. Returns ("", 0) when nothing qualifies. SDKMAN is
// preferred over Homebrew when both provide the same major.
func ResolveServerJavaHome(s *Settings) (home string, major int) {
	for _, c := range installedJavaHomes() {
		if c.major < ServerJavaFloor || c.major > ServerJavaCeiling {
			continue
		}
		if c.major > major {
			home, major = c.home, c.major
		}
	}
	return home, major
}

type javaInstall struct {
	home  string
	major int
}

// installedJavaHomes enumerates JDKs from ~/.sdkman/candidates/java/* and the
// Homebrew openjdk@<major> kegs across the supported range. SDKMAN entries are
// listed first so they win ties in ResolveServerJavaHome.
func installedJavaHomes() []javaInstall {
	var out []javaInstall

	if userHome, err := os.UserHomeDir(); err == nil {
		dir := filepath.Join(userHome, ".sdkman", "candidates", "java")
		if entries, err := os.ReadDir(dir); err == nil {
			for _, e := range entries {
				if !e.IsDir() {
					continue
				}
				m := JavaMajor(e.Name())
				cand := filepath.Join(dir, e.Name())
				if m > 0 && fileExists(filepath.Join(cand, "bin", "java")) {
					out = append(out, javaInstall{home: cand, major: m})
				}
			}
		}
	}

	for major := ServerJavaFloor; major <= ServerJavaCeiling; major++ {
		if home := FindHomebrewJava(strconv.Itoa(major)); home != "" {
			out = append(out, javaInstall{home: home, major: major})
		}
	}

	return out
}

// FindHomebrewJava locates a Homebrew openjdk@<major> keg matching the given
// version's major, checking the common Homebrew prefixes on macOS (Apple
// Silicon and Intel) and Linux. Returns "" when not found.
func FindHomebrewJava(version string) string {
	major := JavaMajor(version)
	if major == 0 {
		return ""
	}
	keg := fmt.Sprintf("openjdk@%d", major)
	for _, prefix := range []string{"/opt/homebrew", "/usr/local", "/home/linuxbrew/.linuxbrew"} {
		home := filepath.Join(prefix, "opt", keg)
		if fileExists(filepath.Join(home, "bin", "java")) {
			return home
		}
	}
	return ""
}

// FindJavaForVersion resolves a JDK home for a specifically requested version,
// trying SDKMAN first (exact, then the major-prefix fallback) then Homebrew.
// Used for the target project's JDK so Homebrew-only users keep auto-discovery.
func FindJavaForVersion(version string) string {
	if home := FindSDKManJava(version); home != "" {
		return home
	}
	return FindHomebrewJava(version)
}

// JavaMajor extracts the major version from a Java version string such as
// "21.0.9-amzn", "17", "8.0.392-amzn", or legacy "1.8.0_392". Returns 0 when
// no major can be parsed.
func JavaMajor(version string) int {
	version = strings.TrimSpace(version)
	lead := leadingInt(version)
	if lead < 0 {
		return 0
	}
	if lead == 1 {
		// Legacy "1.8" style → the real major is the second component.
		rest := strings.TrimPrefix(version[len(strconv.Itoa(lead)):], ".")
		if second := leadingInt(rest); second > 0 {
			return second
		}
	}
	return lead
}

// JavaMajorFromHome best-effort extracts a Java major version from a Java home
// path (a SDKMAN candidate dir name like "21.0.9-amzn" or a Homebrew
// "openjdk@21" keg). Returns 0 when it can't be determined.
func JavaMajorFromHome(home string) int {
	base := filepath.Base(home)
	if at := strings.Index(base, "@"); strings.HasPrefix(base, "openjdk") && at >= 0 {
		return JavaMajor(base[at+1:])
	}
	return JavaMajor(base)
}

// leadingInt returns the integer formed by the leading run of digits in s, or
// -1 if s does not start with a digit.
func leadingInt(s string) int {
	i := 0
	for i < len(s) && s[i] >= '0' && s[i] <= '9' {
		i++
	}
	if i == 0 {
		return -1
	}
	n, err := strconv.Atoi(s[:i])
	if err != nil {
		return -1
	}
	return n
}
