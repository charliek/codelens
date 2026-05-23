// Package state persists and reads server state files keyed by project path.
package state

import (
	"crypto/sha256"
	"encoding/hex"
	"os"
	"path/filepath"
)

// ProjectHash returns the short hash used to key state and log files for a
// project. It mirrors Python's Path.resolve() + sha256()[:12] exactly so
// state files written by either CLI are interchangeable.
//
// Python uses Path.resolve(strict=False), which resolves symlinks but
// tolerates non-existent paths. We mirror that by using EvalSymlinks when the
// path exists and falling back to filepath.Clean otherwise.
func ProjectHash(projectPath string) string {
	canonical := canonicalize(projectPath)
	sum := sha256.Sum256([]byte(canonical))
	return hex.EncodeToString(sum[:])[:12]
}

func canonicalize(p string) string {
	abs, err := filepath.Abs(p)
	if err != nil {
		// Last-resort fallback: clean the original string.
		return filepath.Clean(p)
	}
	// EvalSymlinks resolves real path on disk. If it fails (e.g. path doesn't
	// exist yet), use the cleaned absolute path.
	if _, statErr := os.Lstat(abs); statErr == nil {
		if resolved, err := filepath.EvalSymlinks(abs); err == nil {
			return resolved
		}
	}
	return abs
}
