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
