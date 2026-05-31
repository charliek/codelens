package cli

import (
	"bytes"
	"errors"
	"strings"
	"testing"

	clierrors "github.com/charliek/codelens/cli/internal/errors"
)

// An invalid --scope must fail fast with InvalidUsage (exit 2), before any server
// is contacted — and without diverging from the server's own scope validation.
func TestAnnotationsRejectsInvalidScope(t *testing.T) {
	root := newRootCmd()
	root.SetArgs([]string{"annotations", "usages", "com.example.Foo", "--scope", "bogus"})
	out := &bytes.Buffer{}
	root.SetOut(out)
	root.SetErr(out)

	err := root.Execute()
	if err == nil {
		t.Fatal("expected an error for an invalid --scope")
	}
	var cliErr *clierrors.CLIError
	if !errors.As(err, &cliErr) || cliErr.Code != clierrors.InvalidUsage {
		t.Fatalf("expected an InvalidUsage CLIError, got: %v", err)
	}
	if !strings.Contains(err.Error(), "invalid --scope") {
		t.Fatalf("unexpected error message: %v", err)
	}
}

// Bad pagination must fail fast with InvalidUsage rather than be silently coerced
// (an explicit --size 0 would otherwise become the default 50 client-side).
func TestAnnotationsRejectsBadPagination(t *testing.T) {
	for _, args := range [][]string{
		{"annotations", "usages", "com.example.Foo", "--size", "0"},
		{"annotations", "usages", "com.example.Foo", "--page", "-1"},
	} {
		root := newRootCmd()
		root.SetArgs(args)
		out := &bytes.Buffer{}
		root.SetOut(out)
		root.SetErr(out)

		err := root.Execute()
		if err == nil {
			t.Fatalf("expected an error for %v", args)
		}
		var cliErr *clierrors.CLIError
		if !errors.As(err, &cliErr) || cliErr.Code != clierrors.InvalidUsage {
			t.Fatalf("args %v: expected an InvalidUsage CLIError, got: %v", args, err)
		}
	}
}
