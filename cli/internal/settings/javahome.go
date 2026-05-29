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

// installedJavaHomes enumerates JDKs from all known sources: SDKMAN
// (~/.sdkman/candidates/java/*), mise, Homebrew openjdk@<major> kegs across
// the supported range, and JavaVMs (/Library/Java/JavaVirtualMachines on
// macOS, /usr/lib/jvm on Linux). The order — SDKMAN, mise, Homebrew, JavaVMs
// — is the tie-break order for ResolveServerJavaHome when multiple sources
// provide the same major.
func installedJavaHomes() []javaInstall {
	infos := installedJavaInfos()
	out := make([]javaInstall, 0, len(infos))
	for _, info := range infos {
		out = append(out, javaInstall{home: info.Home, major: info.Major})
	}
	return out
}

// JavaInstallInfo describes a discovered JDK with attribution. The Source
// field is one of "SDKMAN", "Homebrew", "mise", "JavaVMs"; Name is the
// install-dir basename (e.g. "21.0.9-amzn", "openjdk@21", "temurin-21.0.9",
// "temurin-21.jdk").
type JavaInstallInfo struct {
	Home   string
	Major  int
	Source string
	Name   string
}

// installedJavaInfos is the source-of-truth enumeration used by
// installedJavaHomes() and InstalledJavaSummaries(). Order:
// SDKMAN → mise → Homebrew → JavaVMs.
func installedJavaInfos() []JavaInstallInfo {
	var out []JavaInstallInfo

	if userHome, err := os.UserHomeDir(); err == nil {
		dir := filepath.Join(userHome, ".sdkman", "candidates", "java")
		if entries, err := os.ReadDir(dir); err == nil {
			for _, e := range entries {
				if !e.IsDir() || e.Name() == "current" {
					continue
				}
				m := JavaMajor(e.Name())
				cand := filepath.Join(dir, e.Name())
				if m > 0 && fileExists(filepath.Join(cand, "bin", "java")) {
					out = append(out, JavaInstallInfo{
						Home: cand, Major: m, Source: "SDKMAN", Name: e.Name(),
					})
				}
			}
		}
	}

	out = append(out, miseInstalledInfos()...)

	for major := ServerJavaFloor; major <= ServerJavaCeiling; major++ {
		if home := FindHomebrewJava(strconv.Itoa(major)); home != "" {
			out = append(out, JavaInstallInfo{
				Home: home, Major: major, Source: "Homebrew",
				Name: fmt.Sprintf("openjdk@%d", major),
			})
		}
	}

	out = append(out, javaVMInstalledInfos()...)

	return out
}

// InstalledJavaSummaries returns human-readable strings describing every
// discovered JDK install, used in error messages. Format:
// `<name> (<Source> <home>)`, e.g. `21.0.9-amzn (SDKMAN ~/.sdkman/...)`.
// Empty slice when nothing is installed.
func InstalledJavaSummaries() []string {
	infos := installedJavaInfos()
	out := make([]string, 0, len(infos))
	for _, info := range infos {
		out = append(out, fmt.Sprintf("%s (%s %s)", info.Name, info.Source, info.Home))
	}
	return out
}

// FindHomebrewJava locates a Homebrew openjdk@<major> keg matching the given
// version's major. Returns "" when not found.
func FindHomebrewJava(version string) string {
	major := JavaMajor(version)
	if major == 0 {
		return ""
	}
	keg := fmt.Sprintf("openjdk@%d", major)
	for _, prefix := range homebrewPrefixes() {
		home := filepath.Join(prefix, "opt", keg)
		if fileExists(filepath.Join(home, "bin", "java")) {
			return home
		}
	}
	return ""
}

// homebrewPrefixes returns the Homebrew prefixes to search. If HOMEBREW_PREFIX
// is set (Homebrew's shellenv exports it) it is authoritative; otherwise we fall
// back to the common defaults for Apple Silicon, Intel macOS, and Linuxbrew.
func homebrewPrefixes() []string {
	if p := os.Getenv("HOMEBREW_PREFIX"); p != "" {
		return []string{p}
	}
	return []string{"/opt/homebrew", "/usr/local", "/home/linuxbrew/.linuxbrew"}
}

// FindJavaForVersion resolves a JDK home for a specifically requested version,
// trying SDKMAN first (exact, then the major-prefix fallback), then Homebrew
// (openjdk@<major> keg), then JavaVMs (/Library/Java/JavaVirtualMachines on
// macOS, /usr/lib/jvm on Linux — catches cask/DMG-installed JDKs that aren't
// under any package manager's tree), then mise.
func FindJavaForVersion(version string) string {
	home, _ := findJavaForVersionWithSource(version)
	return home
}

// findJavaForVersionWithSource is the internal variant that also reports
// which source matched and whether it required a fallback. Used by
// resolveProjectJava in service.go to surface a same-major substitution to
// the user as a one-line stderr note.
func findJavaForVersionWithSource(version string) (home string, info matchInfo) {
	if home, fellBack := findSDKManJavaWithFallback(version); home != "" {
		return home, matchInfo{source: "SDKMAN", fellBack: fellBack, matchedName: filepath.Base(home)}
	}
	if home := FindHomebrewJava(version); home != "" {
		// Homebrew kegs are always major-only, so a request for "21.0.11-tem"
		// matching "openjdk@21" is always a fallback. Exact match would
		// require version to be e.g. "21" (matches keg directly).
		fellBack := JavaMajor(version) != 0 && !isBareMajorMatch(version, JavaMajor(version))
		return home, matchInfo{source: "Homebrew", fellBack: fellBack, matchedName: filepath.Base(home)}
	}
	if home := FindJavaVMJava(version); home != "" {
		// JavaVMs: detect fallback by comparing requested version to the matched dir name.
		matched := filepath.Base(filepath.Dir(filepath.Dir(home))) // strip Contents/Home on macOS
		if !strings.HasSuffix(home, "Contents/Home") {
			matched = filepath.Base(home) // Linux flat layout
		}
		fellBack := matched != version
		return home, matchInfo{source: "JavaVMs", fellBack: fellBack, matchedName: matched}
	}
	if home := FindMiseJava(version); home != "" {
		// mise's resolver already handles loose matching; treat any non-exact
		// match as a fallback for note purposes.
		base := filepath.Base(home)
		if base == "Home" {
			base = filepath.Base(filepath.Dir(filepath.Dir(home)))
		}
		fellBack := base != version
		return home, matchInfo{source: "mise", fellBack: fellBack, matchedName: base}
	}
	return "", matchInfo{}
}

// matchInfo accompanies a resolved JDK home with attribution and whether the
// match was an exact version hit or a same-major fallback. Internal to the
// settings package; service.go consumes it via ResolveProjectJavaHomeWithMatch.
type matchInfo struct {
	source      string // "SDKMAN" | "Homebrew" | "JavaVMs" | "mise"
	fellBack    bool   // true when the match was not the exact requested version
	matchedName string // dir basename of the resolved install
}

// isBareMajorMatch reports whether a version string is exactly the bare major
// (e.g. "21" matching major 21). Used by findJavaForVersionWithSource to
// decide if a Homebrew keg match counts as exact or a fallback.
func isBareMajorMatch(version string, major int) bool {
	return version == strconv.Itoa(major)
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

// JavaMajorFromHome best-effort extracts a Java major version from a Java
// home path. Recognizes SDKMAN candidate dirs ("21.0.9-amzn"), Homebrew kegs
// ("openjdk@21"), macOS JavaVMs (".../JavaVirtualMachines/<name>/Contents/Home"),
// and Linux jvm dirs ("/usr/lib/jvm/temurin-21-jdk-amd64"). Returns 0 when
// no major can be parsed.
func JavaMajorFromHome(home string) int {
	base := filepath.Base(home)
	// macOS JavaVMs layout: strip trailing Contents/Home and parse the grandparent dir name.
	if base == "Home" && filepath.Base(filepath.Dir(home)) == "Contents" {
		if m := JavaMajorFromVMName(filepath.Base(filepath.Dir(filepath.Dir(home)))); m > 0 {
			return m
		}
	}
	if at := strings.Index(base, "@"); strings.HasPrefix(base, "openjdk") && at >= 0 {
		return JavaMajor(base[at+1:])
	}
	if m := JavaMajor(base); m > 0 {
		return m
	}
	// Linux jvm dirs and other vendor-prefixed names.
	return JavaMajorFromVMName(base)
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
