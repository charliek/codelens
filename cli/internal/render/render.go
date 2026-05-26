// Package render turns CodeLens server responses into human-readable,
// table-style terminal output. It is a pure consumer of the response bytes the
// client already fetched: it decodes them into render-local structs for
// presentation only and never feeds back into the JSON output path, which must
// preserve the server's key order byte-for-byte (see output.PrintRawJSON).
//
// Render structs here are a presentation concern, NOT the locked wire contract.
// They can omit fields safely — a missing field only degrades a table, never
// the JSON. When a payload has no sensible table, a renderer returns
// ErrFallback and the caller emits JSON instead.
package render

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"text/tabwriter"
)

// ErrFallback signals that a renderer has no sensible table for this payload;
// the caller should emit JSON instead.
var ErrFallback = errors.New("render: fall back to JSON")

// newTabwriter returns a tabwriter with CodeLens's standard column padding
// (2-space gaps, no trailing rules).
func newTabwriter(w io.Writer) *tabwriter.Writer {
	return tabwriter.NewWriter(w, 0, 2, 2, ' ', 0)
}

// withTabwriter runs fn against a tabwriter writing to w and flushes it after,
// so callers can't forget the Flush (the classic tabwriter bug).
func withTabwriter(w io.Writer, fn func(tw *tabwriter.Writer) error) error {
	tw := newTabwriter(w)
	if err := fn(tw); err != nil {
		return err
	}
	return tw.Flush()
}

// row writes one tab-separated record followed by a newline.
func row(tw *tabwriter.Writer, cols ...string) {
	fmt.Fprintln(tw, strings.Join(cols, "\t"))
}

// KVBlock writes an aligned key/value block (no box) to w. Keys are emitted in
// the given order — never via map iteration — so output is stable.
func KVBlock(w io.Writer, rows [][2]string) error {
	return withTabwriter(w, func(tw *tabwriter.Writer) error {
		for _, r := range rows {
			row(tw, r[0], r[1])
		}
		return nil
	})
}

// Table renders headers followed by rows as a tab-aligned table. Callers
// pre-format every cell to a string. With no rows, only the header prints.
func Table(w io.Writer, headers []string, rows [][]string) error {
	return withTabwriter(w, func(tw *tabwriter.Writer) error {
		row(tw, headers...)
		for _, r := range rows {
			row(tw, r...)
		}
		return nil
	})
}

// decode unmarshals v (expected to be a json.RawMessage) into T. A non-Raw
// value (e.g. []byte DOT output) or a parse failure yields ErrFallback so the
// caller emits JSON rather than a broken table.
func decode[T any](v any) (T, error) {
	var out T
	raw, ok := v.(json.RawMessage)
	if !ok {
		return out, ErrFallback
	}
	if err := json.Unmarshal(raw, &out); err != nil {
		return out, ErrFallback
	}
	return out, nil
}

// simpleName trims a fully-qualified name to its last dot-separated segment.
func simpleName(fqn string) string {
	if i := strings.LastIndex(fqn, "."); i >= 0 {
		return fqn[i+1:]
	}
	return fqn
}

// typeToken matches a Java/Kotlin identifier path (a run of identifier chars
// and dots), so shortType can replace each qualified name with its last
// segment while leaving generic/array punctuation intact.
var typeToken = regexp.MustCompile(`[\p{L}_$][\p{L}\p{N}_$.]*`)

// shortType strips the package from every qualified type token in a type
// string, preserving generics and arrays:
//
//	ratpack.exec.Promise<java.lang.String>  -> Promise<String>
//	java.util.List<sample.UserService>      -> List<UserService>
//	void / int[] / T                        -> unchanged
func shortType(t string) string {
	return typeToken.ReplaceAllStringFunc(t, func(s string) string {
		if i := strings.LastIndex(s, "."); i >= 0 {
			return s[i+1:]
		}
		return s
	})
}

// dash returns "-" for an empty string (ports the Python `value or "-"` idiom).
func dash(s string) string {
	if s == "" {
		return "-"
	}
	return s
}

// lineStr formats a 1-based source line, rendering a missing line (<= 0, e.g.
// a JSON null decoded to the zero value) as "-".
func lineStr(n int) string {
	if n <= 0 {
		return "-"
	}
	return strconv.Itoa(n)
}

// sortedKeys returns a map's keys in sorted order, so summaries built from a
// map render deterministically.
func sortedKeys[V any](m map[string]V) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	return keys
}
