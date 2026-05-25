package cli

import (
	"bytes"
	"strings"
	"testing"
)

// --descriptor is only meaningful with --method; the command must reject the
// combination during argument validation (before contacting any server).
func TestCallsRejectsDescriptorWithoutMethod(t *testing.T) {
	root := newRootCmd()
	root.SetArgs([]string{"calls", "com.example.Foo", "--descriptor", "(Ljava/lang/String;)V"})
	out := &bytes.Buffer{}
	root.SetOut(out)
	root.SetErr(out)

	err := root.Execute()
	if err == nil {
		t.Fatal("expected an error when --descriptor is given without --method")
	}
	if !strings.Contains(err.Error(), "--descriptor requires --method") {
		t.Fatalf("unexpected error: %v", err)
	}
}
