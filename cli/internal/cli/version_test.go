package cli

import (
	"bytes"
	"strings"
	"testing"

	"github.com/charliek/codelens/cli/internal/version"
)

func TestVersionCommandPrintsVersion(t *testing.T) {
	root := newRootCmd()
	root.SetArgs([]string{"version"})
	out := &bytes.Buffer{}
	root.SetOut(out)
	root.SetErr(out)
	if err := root.Execute(); err != nil {
		t.Fatalf("version command failed: %v", err)
	}
	got := strings.TrimSpace(out.String())
	want := "codelens-cli " + version.Value
	if got != want {
		t.Fatalf("version output: got %q, want %q", got, want)
	}
}
