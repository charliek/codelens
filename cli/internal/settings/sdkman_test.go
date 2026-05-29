package settings

import (
	"os"
	"path/filepath"
	"testing"
)

func TestParseSDKManRC_BasicAndComments(t *testing.T) {
	tmp := t.TempDir()
	path := filepath.Join(tmp, ".sdkmanrc")
	content := `# enable auto-env through the sdkman_auto_env config
java=21.0.9-amzn
gradle=8.5
# comment line
   `
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
	cfg, err := ParseSDKManRC(path)
	if err != nil {
		t.Fatal(err)
	}
	if cfg["java"] != "21.0.9-amzn" {
		t.Errorf("java = %q", cfg["java"])
	}
	if cfg["gradle"] != "8.5" {
		t.Errorf("gradle = %q", cfg["gradle"])
	}
}

func TestParseSDKManRC_PreservesInlineCommentInValue(t *testing.T) {
	// Locked bug-quirk: settings.py:107-130 does NOT strip inline `#`
	// comments from the value, so `java=21.0.9-amzn # comment` keeps the
	// comment. We mirror that exactly.
	tmp := t.TempDir()
	path := filepath.Join(tmp, ".sdkmanrc")
	if err := os.WriteFile(path, []byte("java=21.0.9-amzn # comment\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	cfg, err := ParseSDKManRC(path)
	if err != nil {
		t.Fatal(err)
	}
	if got, want := cfg["java"], "21.0.9-amzn # comment"; got != want {
		t.Errorf("inline comment must be preserved verbatim: got %q, want %q", got, want)
	}
}

func TestParseSDKManRC_MissingFile(t *testing.T) {
	cfg, err := ParseSDKManRC(filepath.Join(t.TempDir(), "missing"))
	if err != nil {
		t.Fatal(err)
	}
	if len(cfg) != 0 {
		t.Errorf("expected empty map; got %v", cfg)
	}
}

func TestFindSDKManJava_PrefixFallback(t *testing.T) {
	// Build a fake SDKMAN layout under a temp HOME.
	tmp := t.TempDir()
	t.Setenv("HOME", tmp)
	dir := filepath.Join(tmp, ".sdkman", "candidates", "java", "21.0.9-amzn", "bin")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "java"), []byte("#!/bin/sh\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	// Exact match.
	got := FindSDKManJava("21.0.9-amzn")
	if filepath.Base(got) != "21.0.9-amzn" {
		t.Errorf("exact = %s", got)
	}
	// Prefix match: requesting "21.0.0-zulu" but only "21.0.9-amzn" exists.
	got = FindSDKManJava("21.0.0-zulu")
	if filepath.Base(got) != "21.0.9-amzn" {
		t.Errorf("prefix fallback = %s", got)
	}
	// No match at all.
	got = FindSDKManJava("17.0.0-tem")
	if got != "" {
		t.Errorf("expected empty; got %s", got)
	}
}

// TestFindSDKManJava_VendorAliasBareMajor is the primary regression test for
// the SplitN bug behind issue #35: requesting "21-tem" (a valid SDKMAN
// vendor-alias meaning "latest Temurin 21", no patch component) must fall
// back to any installed major-21 dir. The old `strings.SplitN("21-tem", ".",
// 2)[0] + "."` produced the prefix "21-tem." which matched nothing.
func TestFindSDKManJava_VendorAliasBareMajor(t *testing.T) {
	tmp := t.TempDir()
	t.Setenv("HOME", tmp)
	fakeSDKManJDK(t, tmp, "21.0.9-amzn")

	for _, in := range []string{"21-tem", "21-amzn", "25-graal"} {
		got, fellBack := findSDKManJavaWithFallback(in)
		if in == "25-graal" {
			// No major-25 installed — must NOT match the 21-* dir.
			if got != "" {
				t.Errorf("%q: expected empty; got %s", in, got)
			}
			continue
		}
		if filepath.Base(got) != "21.0.9-amzn" {
			t.Errorf("%q: expected 21.0.9-amzn; got %s", in, got)
		}
		if !fellBack {
			t.Errorf("%q: expected fellBack=true", in)
		}
	}
}

// TestFindSDKManJava_BareMajorRequest covers `.sdkmanrc: java=21` — a
// bare-major declaration. Should match any installed major-21 JDK.
func TestFindSDKManJava_BareMajorRequest(t *testing.T) {
	tmp := t.TempDir()
	t.Setenv("HOME", tmp)
	fakeSDKManJDK(t, tmp, "21.0.9-amzn")

	got, fellBack := findSDKManJavaWithFallback("21")
	if filepath.Base(got) != "21.0.9-amzn" {
		t.Errorf("got %s, want 21.0.9-amzn", got)
	}
	if !fellBack {
		t.Errorf("expected fellBack=true")
	}
}

// TestFindSDKManJava_BareMajorDirExactMatch covers the rarer case where
// SDKMAN actually installed under a bare-major dir name (e.g. some snapshot
// layouts) — the bare-major branch should match it without falling back.
func TestFindSDKManJava_BareMajorDirExactMatch(t *testing.T) {
	tmp := t.TempDir()
	t.Setenv("HOME", tmp)
	fakeSDKManJDK(t, tmp, "21") // dir literally named "21"

	got, fellBack := findSDKManJavaWithFallback("21-tem")
	if filepath.Base(got) != "21" {
		t.Errorf("got %s, want 21", got)
	}
	if !fellBack {
		// The request was "21-tem" but matched dir "21" — that's still a
		// substitution, not an exact match.
		t.Errorf("expected fellBack=true")
	}
}

// TestFindSDKManJava_DifferentMajorRejected guards against over-matching.
// "17-tem" must not pick up a major-21 install.
func TestFindSDKManJava_DifferentMajorRejected(t *testing.T) {
	tmp := t.TempDir()
	t.Setenv("HOME", tmp)
	fakeSDKManJDK(t, tmp, "21.0.9-amzn")

	if got := FindSDKManJava("17-tem"); got != "" {
		t.Errorf("17-tem should NOT match 21-* installs; got %s", got)
	}
}

// TestFindSDKManJava_Issue35Scenario is a permanent regression anchor for
// the literal scenario in https://github.com/charliek/codelens/issues/35:
// project declares 21.0.11-tem, only 21.0.9-amzn is installed → must
// resolve to 21.0.9-amzn via the same-major fallback.
func TestFindSDKManJava_Issue35Scenario(t *testing.T) {
	tmp := t.TempDir()
	t.Setenv("HOME", tmp)
	fakeSDKManJDK(t, tmp, "21.0.9-amzn")

	got, fellBack := findSDKManJavaWithFallback("21.0.11-tem")
	if filepath.Base(got) != "21.0.9-amzn" {
		t.Errorf("got %s, want 21.0.9-amzn", got)
	}
	if !fellBack {
		t.Errorf("expected fellBack=true (different vendor + patch)")
	}
}

// TestFindSDKManJava_InlineCommentInVersion confirms that a declared
// version carrying a preserved-comment suffix (the .sdkmanrc parser keeps
// inline `# comment` text verbatim, matching Python) still resolves via the
// same-major fallback because JavaMajor strips correctly.
func TestFindSDKManJava_InlineCommentInVersion(t *testing.T) {
	tmp := t.TempDir()
	t.Setenv("HOME", tmp)
	fakeSDKManJDK(t, tmp, "21.0.9-amzn")

	if got := FindSDKManJava("21.0.11-tem # comment"); filepath.Base(got) != "21.0.9-amzn" {
		t.Errorf("got %s, want 21.0.9-amzn", got)
	}
}
