// Package version exposes the CodeLens CLI version, read from the
// repository-root version.txt at build time.
package version

import (
	_ "embed"
	"strings"
)

//go:generate sh -c "cp ../../../version.txt ./version.txt"

//go:embed version.txt
var raw string

// Value is the trimmed version string (e.g. "0.1.0").
var Value = strings.TrimSpace(raw)
