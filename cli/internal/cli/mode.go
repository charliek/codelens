package cli

import (
	"os"

	"github.com/charliek/codelens/cli/internal/output"
)

// outputMode selects how a command renders its result.
type outputMode int

const (
	modeJSON outputMode = iota
	modeTable
)

// stdoutIsTTY reports whether real stdout is attached to a terminal. It is a
// var so tests can force table mode: under `go test` (and the e2e harness)
// os.Stdout is a pipe, never a TTY, so the default stays JSON and existing
// goldens are unaffected.
var stdoutIsTTY = func() bool { return output.IsTTY(os.Stdout) }

// resolveMode picks the output mode. Precedence: an explicit --json wins, then
// an explicit --table, then autodetect — a table on a terminal, JSON when
// piped or redirected. --json and --table are mutually exclusive (enforced by
// cobra), so at most one is set here.
func resolveMode() outputMode {
	switch {
	case flagJSON:
		return modeJSON
	case flagTable:
		return modeTable
	case stdoutIsTTY():
		return modeTable
	default:
		return modeJSON
	}
}
