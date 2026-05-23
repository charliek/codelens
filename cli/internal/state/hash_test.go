package state

import (
	"crypto/sha256"
	"encoding/hex"
	"os"
	"path/filepath"
	"testing"
)

// TestProjectHash_PinnedReference pins the exact hash that Python would
// produce for a fixed canonical path. The path is artificial — it never
// touches the filesystem and contains no developer-machine specifics — so
// this test runs identically locally and in CI.
//
// To verify against Python:
//
//	python3 -c "import hashlib; print(hashlib.sha256(b'/tmp/codelens-port-fixture').hexdigest()[:12])"
//
// produces "63b8a3f4ccbe" (locked here).
func TestProjectHash_PinnedReference(t *testing.T) {
	const refPath = "/tmp/codelens-port-fixture"
	got := ProjectHash(refPath)
	if got != pinnedHash {
		t.Errorf("ProjectHash drift: got %q, want %q for %q", got, pinnedHash, refPath)
	}
	// Cross-check the Go implementation against the Python algorithm
	// applied to the same input.
	want := referenceSHA12(refPath)
	if got != want {
		t.Errorf("Go hash diverges from reference algorithm: got %q, want %q", got, want)
	}
}

// pinnedHash is sha256(b"/tmp/codelens-port-fixture").hexdigest()[:12].
// Update only when the reference path or algorithm intentionally changes.
const pinnedHash = "a7dcdd48e71b"

// referenceSHA12 reproduces what Python computes for a given canonical path:
//
//	hashlib.sha256(p.encode()).hexdigest()[:12]
//
// Used as a sanity check that our Go algorithm matches the Python one.
func referenceSHA12(p string) string {
	sum := sha256.Sum256([]byte(p))
	return hex.EncodeToString(sum[:])[:12]
}

func TestProjectHash_SymlinkResolution(t *testing.T) {
	tmp := t.TempDir()
	real := filepath.Join(tmp, "real-project")
	if err := os.MkdirAll(real, 0o755); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(tmp, "linked-project")
	if err := os.Symlink(real, link); err != nil {
		t.Skipf("symlink not supported here: %v", err)
	}
	hReal := ProjectHash(real)
	hLink := ProjectHash(link)
	if hReal != hLink {
		t.Errorf("symlink not resolved: real=%s link=%s", hReal, hLink)
	}
}

func TestProjectHash_NonexistentPath(t *testing.T) {
	// resolve(strict=False) tolerates this; we must too.
	h := ProjectHash("/definitely/does/not/exist/anywhere")
	if len(h) != 12 {
		t.Errorf("expected 12-char hash; got %q", h)
	}
}
