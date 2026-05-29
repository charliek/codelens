package settings

import (
	"os"
	"path/filepath"
	"testing"
)

func TestJavaMajor(t *testing.T) {
	cases := map[string]int{
		"21.0.9-amzn":  21,
		"25-amzn":      25,
		"25":           25,
		"17":           17,
		"8.0.392-amzn": 8,
		"1.8.0_392":    8,
		"":             0,
		"temurin":      0,
		"11.0.20+8":    11,
	}
	for in, want := range cases {
		if got := JavaMajor(in); got != want {
			t.Errorf("JavaMajor(%q) = %d, want %d", in, got, want)
		}
	}
}

func TestJavaMajorFromHome(t *testing.T) {
	cases := map[string]int{
		"/Users/dev/.sdkman/candidates/java/21.0.9-amzn": 21,
		"/opt/homebrew/opt/openjdk@21":                   21,
		"/usr/local/opt/openjdk@25":                      25,
		"/usr/lib/jvm/whatever":                          0,
	}
	for in, want := range cases {
		if got := JavaMajorFromHome(in); got != want {
			t.Errorf("JavaMajorFromHome(%q) = %d, want %d", in, got, want)
		}
	}
}

// fakeJDK creates ~/.sdkman/candidates/java/<name>/bin/java under home.
func fakeSDKManJDK(t *testing.T, home, name string) {
	t.Helper()
	dir := filepath.Join(home, ".sdkman", "candidates", "java", name, "bin")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "java"), []byte("#!/bin/sh\n"), 0o755); err != nil {
		t.Fatal(err)
	}
}

func TestResolveServerJavaHome_PicksHighestInRange(t *testing.T) {
	tmp := t.TempDir()
	t.Setenv("HOME", tmp)
	t.Setenv("HOMEBREW_PREFIX", t.TempDir())      // isolate from any system Homebrew JDKs
	t.Setenv("CODELENS_JAVA_VM_DIRS", t.TempDir()) // isolate from any system /Library/Java or /usr/lib/jvm
	t.Setenv("MISE_DATA_DIR", t.TempDir())         // isolate from any system mise installs
	// 17 is below the floor, 26 is above the ceiling; 21 and 25 are in range.
	fakeSDKManJDK(t, tmp, "17.0.10-tem")
	fakeSDKManJDK(t, tmp, "21.0.9-amzn")
	fakeSDKManJDK(t, tmp, "25-amzn")
	fakeSDKManJDK(t, tmp, "26-open")

	home, major := ResolveServerJavaHome(&Settings{})
	if major != 25 {
		t.Fatalf("expected major 25, got %d (home %s)", major, home)
	}
	if filepath.Base(home) != "25-amzn" {
		t.Errorf("expected 25-amzn home, got %s", home)
	}
}

func TestResolveServerJavaHome_NoneInRange(t *testing.T) {
	tmp := t.TempDir()
	t.Setenv("HOME", tmp)
	t.Setenv("HOMEBREW_PREFIX", t.TempDir())       // isolate from any system Homebrew JDKs
	t.Setenv("CODELENS_JAVA_VM_DIRS", t.TempDir()) // isolate from system /Library/Java or /usr/lib/jvm
	t.Setenv("MISE_DATA_DIR", t.TempDir())         // isolate from system mise installs
	fakeSDKManJDK(t, tmp, "17.0.10-tem")
	fakeSDKManJDK(t, tmp, "26-open")

	if home, major := ResolveServerJavaHome(&Settings{}); home != "" || major != 0 {
		t.Errorf("expected no in-range JDK; got home=%q major=%d", home, major)
	}
}

func TestFindJavaForVersion_SDKManThenEmpty(t *testing.T) {
	tmp := t.TempDir()
	t.Setenv("HOME", tmp)
	t.Setenv("HOMEBREW_PREFIX", t.TempDir())       // isolate from any system Homebrew JDKs
	t.Setenv("CODELENS_JAVA_VM_DIRS", t.TempDir()) // isolate from system /Library/Java or /usr/lib/jvm
	t.Setenv("MISE_DATA_DIR", t.TempDir())         // isolate from system mise installs
	fakeSDKManJDK(t, tmp, "17.0.10-tem")

	if got := FindJavaForVersion("17.0.10-tem"); filepath.Base(got) != "17.0.10-tem" {
		t.Errorf("expected SDKMAN 17 home; got %s", got)
	}
	// Not installed in SDKMAN and no Homebrew keg in the isolated prefix → "".
	if got := FindJavaForVersion("8.0.392-amzn"); got != "" {
		t.Errorf("expected empty; got %s", got)
	}
}
