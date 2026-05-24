package settings

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestMiseProjectJavaVersion(t *testing.T) {
	cases := []struct {
		name    string
		file    string
		content string
		want    string
	}{
		{"mise.toml string", ".mise.toml", "[tools]\njava = \"21.0.9-amzn\"\n", "21.0.9-amzn"},
		{"mise.toml array", ".mise.toml", "[tools]\njava = ['temurin-21']\n", "temurin-21"},
		{"mise.toml inline table", "mise.toml", "[tools]\njava = { version = \"17\" }\n", "17"},
		{"mise.toml bare", ".mise.toml", "[tools]\njava = 21\n", "21"},
		{"tool-versions", ".tool-versions", "java temurin-17.0.10\nnode 20\n", "temurin-17.0.10"},
		{"tool-versions multi", ".tool-versions", "java 21.0.5 17.0.10\n", "21.0.5"},
		{"java outside tools table ignored", ".mise.toml", "[env]\njava = \"99\"\n", ""},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			dir := t.TempDir()
			if err := os.WriteFile(filepath.Join(dir, c.file), []byte(c.content), 0o644); err != nil {
				t.Fatal(err)
			}
			if got := MiseProjectJavaVersion(dir); got != c.want {
				t.Errorf("got %q, want %q", got, c.want)
			}
		})
	}

	// No mise config → empty.
	if got := MiseProjectJavaVersion(t.TempDir()); got != "" {
		t.Errorf("expected empty for no mise config; got %q", got)
	}
}

func TestFindMiseJava(t *testing.T) {
	data := t.TempDir()
	t.Setenv("MISE_DATA_DIR", data)
	installs := filepath.Join(data, "installs", "java")

	// A flat-layout install and a macOS Contents/Home-layout install.
	mkJava := func(version string, contentsHome bool) {
		base := filepath.Join(installs, version)
		binDir := base
		if contentsHome {
			binDir = filepath.Join(base, "Contents", "Home")
		}
		if err := os.MkdirAll(filepath.Join(binDir, "bin"), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(binDir, "bin", "java"), []byte("#!/bin/sh\n"), 0o755); err != nil {
			t.Fatal(err)
		}
	}
	mkJava("temurin-21.0.9", false)
	mkJava("corretto-17.0.10", true)

	// Exact match.
	if got := FindMiseJava("temurin-21.0.9"); filepath.Base(got) != "temurin-21.0.9" {
		t.Errorf("exact: got %q", got)
	}
	// Prefix match by distro+major.
	if got := FindMiseJava("temurin-21"); filepath.Base(got) != "temurin-21.0.9" {
		t.Errorf("prefix: got %q", got)
	}
	// Contents/Home layout resolves to the inner home.
	got := FindMiseJava("corretto-17.0.10")
	if !fileExists(filepath.Join(got, "bin", "java")) {
		t.Errorf("Contents/Home: resolved home has no bin/java: %q", got)
	}
	// Major-only request matches the right install (resolved to its inner home).
	if got := FindMiseJava("17"); !strings.Contains(got, "corretto-17.0.10") || !fileExists(filepath.Join(got, "bin", "java")) {
		t.Errorf("major-only: got %q", got)
	}
	// Unknown → empty.
	if got := FindMiseJava("8.0.1-zulu"); got != "" {
		t.Errorf("expected empty; got %q", got)
	}
}

func TestMiseMajor(t *testing.T) {
	cases := map[string]int{
		"temurin-21.0.9":       21,
		"21.0.9":               21,
		"corretto-17.0.10.7.1": 17,
		"graalvm-community-21": 21,
		"no-digits":            0,
	}
	for in, want := range cases {
		if got := miseMajor(in); got != want {
			t.Errorf("miseMajor(%q) = %d, want %d", in, got, want)
		}
	}
}
