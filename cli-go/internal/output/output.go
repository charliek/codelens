// Package output centralizes JSON / table rendering and TTY detection.
package output

import (
	"encoding/json"
	"io"
	"os"
)

// IsTTY reports whether the given file is attached to a terminal.
func IsTTY(f *os.File) bool {
	fi, err := f.Stat()
	if err != nil {
		return false
	}
	return (fi.Mode() & os.ModeCharDevice) != 0
}

// PrintJSON writes v as indented JSON to w. SetEscapeHTML(false) so that
// source content containing <, >, & matches Python's json.dumps output
// byte-for-byte.
func PrintJSON(w io.Writer, v any) error {
	enc := json.NewEncoder(w)
	enc.SetIndent("", "  ")
	enc.SetEscapeHTML(false)
	return enc.Encode(v)
}

// PrintRawJSON re-indents an already-serialized JSON payload (typically a
// json.RawMessage from the HTTP client) so callers using --json get
// consistently-formatted output.
func PrintRawJSON(w io.Writer, raw []byte) error {
	if len(raw) == 0 {
		return nil
	}
	var v any
	if err := json.Unmarshal(raw, &v); err != nil {
		// Not parseable JSON — emit verbatim.
		_, err := w.Write(raw)
		return err
	}
	return PrintJSON(w, v)
}
