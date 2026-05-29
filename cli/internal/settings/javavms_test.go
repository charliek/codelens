package settings

import (
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

// fakeMacOSVMJDK creates `<root>/<name>/Contents/Home/bin/java` and returns
// the install dir under root (i.e. `<root>/<name>`).
func fakeMacOSVMJDK(t *testing.T, root, name string) string {
	t.Helper()
	dir := filepath.Join(root, name, "Contents", "Home", "bin")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "java"), []byte("#!/bin/sh\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	return filepath.Join(root, name)
}

// fakeLinuxJVMJDK creates `<root>/<name>/bin/java` (flat layout, no Contents/Home).
func fakeLinuxJVMJDK(t *testing.T, root, name string) string {
	t.Helper()
	dir := filepath.Join(root, name, "bin")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "java"), []byte("#!/bin/sh\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	return filepath.Join(root, name)
}

func TestJavaMajorFromVMName(t *testing.T) {
	cases := map[string]int{
		"temurin-21.jdk":              21,
		"temurin-21.0.5+11.jdk":       21,
		"amazon-corretto-21.jdk":      21,
		"amazon-corretto-17.0.10.jdk": 17,
		"zulu-21.jdk":                 21,
		"jdk-21.0.5.jdk":              21,
		"liberica-21.jdk":             21,
		"microsoft-21.jdk":            21,
		"openjdk-25.jdk":              25,
		"oracle-21.jdk":               21,
		"semeru-21.jdk":               21,
		"graalvm-ce-java21-22.3.1":    21,
		"graalvm-community-java21":    21,
		"temurin-21-jdk-amd64":        21, // Linux flat layout
		"java-21-openjdk-amd64":       21, // Linux Debian-style
		"21.0.9-amzn":                 21, // raw SDKMAN-style (no vendor prefix)
		"25-amzn":                     25,
		"notajdk":                     0,
		"":                            0,
	}
	for in, want := range cases {
		if got := JavaMajorFromVMName(in); got != want {
			t.Errorf("JavaMajorFromVMName(%q) = %d, want %d", in, got, want)
		}
	}
}

func TestJavaMajorFromHome_JavaVMsPath(t *testing.T) {
	// macOS layout.
	got := JavaMajorFromHome("/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home")
	if got != 21 {
		t.Errorf("got %d, want 21", got)
	}
	got = JavaMajorFromHome("/Library/Java/JavaVirtualMachines/amazon-corretto-17.jdk/Contents/Home")
	if got != 17 {
		t.Errorf("got %d, want 17", got)
	}
	// Linux flat layout — basename is the dir name itself.
	got = JavaMajorFromHome("/usr/lib/jvm/temurin-21-jdk-amd64")
	if got != 21 {
		t.Errorf("Linux: got %d, want 21", got)
	}
	// Still handles existing inputs.
	got = JavaMajorFromHome("/Users/dev/.sdkman/candidates/java/21.0.9-amzn")
	if got != 21 {
		t.Errorf("SDKMAN: got %d, want 21", got)
	}
	got = JavaMajorFromHome("/opt/homebrew/opt/openjdk@25")
	if got != 25 {
		t.Errorf("Homebrew: got %d, want 25", got)
	}
}

func TestJavaVMInstalledInfos_macOS(t *testing.T) {
	tmp := t.TempDir()
	t.Setenv("CODELENS_JAVA_VM_DIRS", tmp)
	fakeMacOSVMJDK(t, tmp, "temurin-21.jdk")
	fakeMacOSVMJDK(t, tmp, "amazon-corretto-21.jdk")
	fakeMacOSVMJDK(t, tmp, "jdk-25.jdk")
	fakeMacOSVMJDK(t, tmp, "zulu-17.jdk")
	// A garbage dir without bin/java — should be skipped.
	if err := os.MkdirAll(filepath.Join(tmp, "not-a-jdk"), 0o755); err != nil {
		t.Fatal(err)
	}

	infos := javaVMInstalledInfos()
	if len(infos) != 4 {
		t.Fatalf("expected 4 entries, got %d: %+v", len(infos), infos)
	}
	gotMajors := map[int]string{}
	for _, info := range infos {
		gotMajors[info.Major] = info.Name
		if info.Source != "JavaVMs" {
			t.Errorf("Source = %q, want JavaVMs", info.Source)
		}
		// Home must end with Contents/Home and contain bin/java.
		if !strings.HasSuffix(info.Home, "Contents/Home") {
			t.Errorf("Home = %q, expected to end with Contents/Home", info.Home)
		}
		if !fileExists(filepath.Join(info.Home, "bin", "java")) {
			t.Errorf("Home = %q, bin/java missing", info.Home)
		}
	}
	for _, m := range []int{17, 21, 25} {
		if _, ok := gotMajors[m]; !ok {
			t.Errorf("missing major %d (got: %v)", m, gotMajors)
		}
	}
}

func TestJavaVMInstalledInfos_DedupeSymlinks(t *testing.T) {
	tmp := t.TempDir()
	t.Setenv("CODELENS_JAVA_VM_DIRS", tmp)
	real := fakeLinuxJVMJDK(t, tmp, "temurin-21-jdk-amd64")
	// Create symlink default-java -> temurin-21-jdk-amd64.
	if err := os.Symlink(real, filepath.Join(tmp, "default-java")); err != nil {
		t.Fatal(err)
	}

	infos := javaVMInstalledInfos()
	if len(infos) != 1 {
		t.Fatalf("expected 1 entry (dedupe), got %d: %+v", len(infos), infos)
	}
	if infos[0].Major != 21 {
		t.Errorf("major = %d, want 21", infos[0].Major)
	}
}

func TestJavaVMInstalledInfos_SkipsBrokenSymlinks(t *testing.T) {
	tmp := t.TempDir()
	t.Setenv("CODELENS_JAVA_VM_DIRS", tmp)
	if err := os.Symlink(filepath.Join(tmp, "nonexistent"), filepath.Join(tmp, "broken")); err != nil {
		t.Fatal(err)
	}
	fakeLinuxJVMJDK(t, tmp, "temurin-21-jdk-amd64")

	infos := javaVMInstalledInfos()
	if len(infos) != 1 {
		t.Fatalf("expected 1 entry (broken skipped), got %d: %+v", len(infos), infos)
	}
}

func TestFindJavaVMJava_ExactNameWins(t *testing.T) {
	tmp := t.TempDir()
	t.Setenv("CODELENS_JAVA_VM_DIRS", tmp)
	fakeMacOSVMJDK(t, tmp, "temurin-21.jdk")
	fakeMacOSVMJDK(t, tmp, "amazon-corretto-21.jdk")

	got := FindJavaVMJava("temurin-21.jdk")
	if !strings.Contains(got, "temurin-21.jdk") {
		t.Errorf("expected temurin-21.jdk match, got %q", got)
	}
}

func TestFindJavaVMJava_SameMajorFallback(t *testing.T) {
	tmp := t.TempDir()
	t.Setenv("CODELENS_JAVA_VM_DIRS", tmp)
	fakeMacOSVMJDK(t, tmp, "amazon-corretto-21.jdk")

	cases := []string{"21", "21-tem", "21.0.5-tem", "21.0.9-amzn"}
	for _, in := range cases {
		got := FindJavaVMJava(in)
		if !strings.Contains(got, "amazon-corretto-21.jdk") {
			t.Errorf("FindJavaVMJava(%q) = %q, want a path containing amazon-corretto-21.jdk", in, got)
		}
	}
}

func TestFindJavaVMJava_DifferentMajorRejected(t *testing.T) {
	tmp := t.TempDir()
	t.Setenv("CODELENS_JAVA_VM_DIRS", tmp)
	fakeMacOSVMJDK(t, tmp, "temurin-21.jdk")

	if got := FindJavaVMJava("17-tem"); got != "" {
		t.Errorf("17-tem should NOT match a major-21 install; got %q", got)
	}
	if got := FindJavaVMJava(""); got != "" {
		t.Errorf("empty version should return empty; got %q", got)
	}
}

// FindJavaVMJava must same-major-match a vendor-prefixed mise-style version
// request (e.g. "temurin-21.0.9" from a `.tool-versions` declaration), not
// only bare-major or SDKMAN-style requests. Regression for PR #37 review.
func TestFindJavaVMJava_VendorPrefixedRequest(t *testing.T) {
	tmp := t.TempDir()
	t.Setenv("CODELENS_JAVA_VM_DIRS", tmp)
	fakeMacOSVMJDK(t, tmp, "amazon-corretto-21.jdk")

	// "temurin-21.0.9" → JavaMajor returns 0 (vendor prefix isn't a digit),
	// so the resolver must fall through to JavaMajorFromVMName which extracts 21.
	for _, in := range []string{"temurin-21.0.9", "corretto-21", "zulu-21.0.5"} {
		got := FindJavaVMJava(in)
		if !strings.Contains(got, "amazon-corretto-21.jdk") {
			t.Errorf("FindJavaVMJava(%q) = %q, want a match against amazon-corretto-21.jdk", in, got)
		}
	}
}

func TestFindJavaVMJava_LinuxLayout(t *testing.T) {
	tmp := t.TempDir()
	t.Setenv("CODELENS_JAVA_VM_DIRS", tmp)
	fakeLinuxJVMJDK(t, tmp, "temurin-21-jdk-amd64")

	got := FindJavaVMJava("21-tem")
	if got != filepath.Join(tmp, "temurin-21-jdk-amd64") {
		t.Errorf("got %q, want %s", got, filepath.Join(tmp, "temurin-21-jdk-amd64"))
	}
}

func TestResolveServerJavaHome_JavaVMsOnly(t *testing.T) {
	tmp := t.TempDir()
	t.Setenv("HOME", tmp)                    // empty SDKMAN
	t.Setenv("HOMEBREW_PREFIX", t.TempDir()) // empty Homebrew
	t.Setenv("MISE_DATA_DIR", t.TempDir())   // empty mise
	t.Setenv("CODELENS_JAVA_VM_DIRS", t.TempDir())
	// Drop one JavaVMs install in the configured dir.
	vmRoot := os.Getenv("CODELENS_JAVA_VM_DIRS")
	fakeMacOSVMJDK(t, vmRoot, "temurin-21.jdk")

	home, major := ResolveServerJavaHome(&Settings{})
	if major != 21 {
		t.Fatalf("major = %d, want 21 (home %s)", major, home)
	}
	if !strings.HasSuffix(home, "Contents/Home") {
		t.Errorf("home should end with Contents/Home; got %q", home)
	}
}

func TestResolveServerJavaHome_SDKManBeatsJavaVMs(t *testing.T) {
	tmp := t.TempDir()
	t.Setenv("HOME", tmp)
	t.Setenv("HOMEBREW_PREFIX", t.TempDir())
	t.Setenv("MISE_DATA_DIR", t.TempDir())
	t.Setenv("CODELENS_JAVA_VM_DIRS", t.TempDir())
	fakeSDKManJDK(t, tmp, "21.0.9-amzn")
	vmRoot := os.Getenv("CODELENS_JAVA_VM_DIRS")
	fakeMacOSVMJDK(t, vmRoot, "temurin-21.jdk")

	home, major := ResolveServerJavaHome(&Settings{})
	if major != 21 {
		t.Fatalf("major = %d, want 21", major)
	}
	if !strings.Contains(home, ".sdkman") {
		t.Errorf("expected SDKMAN to win on ties; got %q", home)
	}
}

// fakeHomebrewKeg drops a minimal `<prefix>/opt/openjdk@<major>/bin/java` so
// FindHomebrewJava finds it via HOMEBREW_PREFIX. Returns the keg path.
func fakeHomebrewKeg(t *testing.T, prefix string, major int) string {
	t.Helper()
	keg := filepath.Join(prefix, "opt", fmt.Sprintf("openjdk@%d", major))
	dir := filepath.Join(keg, "bin")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "java"), []byte("#!/bin/sh\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	return keg
}

// InstalledJavaSummaries must include Homebrew kegs OUTSIDE the
// server-JVM range (8, 11, 17) so the error message for a project
// declaring an older Java (e.g. java=8.0.392-amzn) tells the user about
// their `openjdk@8` install. Regression for PR #37 review — previously
// installedJavaInfos only enumerated Homebrew in [21, 25].
func TestInstalledJavaSummaries_IncludesOlderHomebrewKegs(t *testing.T) {
	tmp := t.TempDir()
	t.Setenv("HOME", tmp)
	t.Setenv("HOMEBREW_PREFIX", tmp)
	t.Setenv("MISE_DATA_DIR", t.TempDir())
	t.Setenv("CODELENS_JAVA_VM_DIRS", t.TempDir())

	fakeHomebrewKeg(t, tmp, 8)
	fakeHomebrewKeg(t, tmp, 17)
	fakeHomebrewKeg(t, tmp, 21)

	got := InstalledJavaSummaries()
	joined := strings.Join(got, "\n")
	for _, want := range []string{"openjdk@8", "openjdk@17", "openjdk@21"} {
		if !strings.Contains(joined, want) {
			t.Errorf("missing %q in summaries:\n%s", want, joined)
		}
	}
}

// The broadened Homebrew enumeration must NOT affect server-JVM
// selection — ResolveServerJavaHome should still pick the highest in
// [ServerJavaFloor, ServerJavaCeiling] = [21, 25] and ignore older kegs.
func TestResolveServerJavaHome_IgnoresOlderHomebrewKegs(t *testing.T) {
	tmp := t.TempDir()
	t.Setenv("HOME", tmp)
	t.Setenv("HOMEBREW_PREFIX", tmp)
	t.Setenv("MISE_DATA_DIR", t.TempDir())
	t.Setenv("CODELENS_JAVA_VM_DIRS", t.TempDir())

	fakeHomebrewKeg(t, tmp, 8)
	fakeHomebrewKeg(t, tmp, 17)
	fakeHomebrewKeg(t, tmp, 21)

	home, major := ResolveServerJavaHome(&Settings{})
	if major != 21 {
		t.Fatalf("major = %d, want 21 (8 and 17 are below floor)", major)
	}
	if !strings.Contains(home, "openjdk@21") {
		t.Errorf("home = %q, want openjdk@21", home)
	}
}

func TestInstalledJavaSummaries_AttributionAndFormat(t *testing.T) {
	tmp := t.TempDir()
	t.Setenv("HOME", tmp)
	t.Setenv("HOMEBREW_PREFIX", t.TempDir())
	t.Setenv("MISE_DATA_DIR", t.TempDir())
	t.Setenv("CODELENS_JAVA_VM_DIRS", t.TempDir())
	fakeSDKManJDK(t, tmp, "21.0.9-amzn")
	vmRoot := os.Getenv("CODELENS_JAVA_VM_DIRS")
	fakeMacOSVMJDK(t, vmRoot, "temurin-21.jdk")

	got := InstalledJavaSummaries()
	if len(got) < 2 {
		t.Fatalf("expected at least 2 entries, got %d: %v", len(got), got)
	}
	// Each entry must contain the source tag and the name.
	joined := strings.Join(got, "\n")
	for _, want := range []string{"21.0.9-amzn", "(SDKMAN", "temurin-21.jdk", "(JavaVMs"} {
		if !strings.Contains(joined, want) {
			t.Errorf("missing %q in:\n%s", want, joined)
		}
	}
}

func TestInstalledJavaSummaries_Empty(t *testing.T) {
	tmp := t.TempDir()
	t.Setenv("HOME", tmp)
	t.Setenv("HOMEBREW_PREFIX", t.TempDir())
	t.Setenv("MISE_DATA_DIR", t.TempDir())
	t.Setenv("CODELENS_JAVA_VM_DIRS", t.TempDir())
	if got := InstalledJavaSummaries(); len(got) != 0 {
		t.Errorf("expected empty; got %v", got)
	}
}

func TestJavaVMDirs_EnvOverride(t *testing.T) {
	t.Setenv("CODELENS_JAVA_VM_DIRS", "/a,/b , /c")
	got := javaVMDirs()
	want := []string{"/a", "/b", "/c"}
	if len(got) != len(want) {
		t.Fatalf("len = %d, want %d (%v)", len(got), len(want), got)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("got[%d] = %q, want %q", i, got[i], want[i])
		}
	}
}

func TestJavaVMDirs_DefaultByGOOS(t *testing.T) {
	t.Setenv("CODELENS_JAVA_VM_DIRS", "") // explicit clear
	got := javaVMDirs()
	switch runtime.GOOS {
	case "darwin":
		if len(got) < 1 || got[0] != "/Library/Java/JavaVirtualMachines" {
			t.Errorf("macOS default missing: got %v", got)
		}
	case "linux":
		if len(got) != 1 || got[0] != "/usr/lib/jvm" {
			t.Errorf("Linux default: got %v", got)
		}
	}
}
