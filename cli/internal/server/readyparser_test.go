package server

import (
	"context"
	"errors"
	"io"
	"strings"
	"testing"
	"time"
)

func TestParseLine_ReadyHappyPath(t *testing.T) {
	info, err := ParseLine("CODELENS_READY port=8080 host=127.0.0.1 version=1.0.0")
	if err != nil || info == nil {
		t.Fatalf("expected info, got info=%v err=%v", info, err)
	}
	if info.Port != 8080 || info.Host != "127.0.0.1" || info.Version != "1.0.0" {
		t.Errorf("info = %+v", info)
	}
}

// Python uses re.search so any prefix is fine (e.g. log timestamps).
func TestParseLine_AcceptsPrefixNoise(t *testing.T) {
	info, err := ParseLine("[INFO] 2026-05-23 CODELENS_READY port=9090 host=127.0.0.1 version=1.2.3")
	if err != nil || info == nil {
		t.Fatalf("prefix-noise line should still parse READY: info=%v err=%v", info, err)
	}
	if info.Port != 9090 {
		t.Errorf("port = %d", info.Port)
	}
}

func TestParseLine_StartingIsIgnored(t *testing.T) {
	info, err := ParseLine("CODELENS_STARTING port=8080 host=127.0.0.1")
	if info != nil || err != nil {
		t.Errorf("STARTING should be ignored; got info=%v err=%v", info, err)
	}
}

func TestParseLine_ErrorReason(t *testing.T) {
	_, err := ParseLine(`CODELENS_ERROR reason=SCAN message="something bad"`)
	if err == nil {
		t.Fatal("expected ScanError")
	}
	var se *ScanError
	if !errors.As(err, &se) {
		t.Fatalf("expected *ScanError; got %T", err)
	}
	if se.Reason != "SCAN" || se.Message != "something bad" {
		t.Errorf("scan err = %+v", se)
	}
}

// Locked bug-quirk: the regex [^"]* truncates at the first embedded `"`.
func TestParseLine_ErrorWithEmbeddedQuoteTruncates(t *testing.T) {
	line := `CODELENS_ERROR reason=X message="he said \"hi\""`
	_, err := ParseLine(line)
	var se *ScanError
	if !errors.As(err, &se) {
		t.Fatalf("expected ScanError; got %v", err)
	}
	// Python's [^"]* captures "he said \" — everything up to the first
	// inner quote. (The backslash is matched literally because Python's
	// regex doesn't interpret backslash-quote inside the character class.)
	if se.Message != `he said \` {
		t.Errorf("truncation mismatch: got %q", se.Message)
	}
}

func TestParseLine_ReadyWinsOverErrorOnSameLine(t *testing.T) {
	line := `CODELENS_READY port=1 host=h version=v and also CODELENS_ERROR reason=R message="boom"`
	info, err := ParseLine(line)
	if err != nil || info == nil {
		t.Fatalf("READY must win when both present; got info=%v err=%v", info, err)
	}
}

func TestParseLine_UnrelatedLineReturnsNothing(t *testing.T) {
	info, err := ParseLine("Starting up the world...")
	if info != nil || err != nil {
		t.Errorf("plain line should be ignored: info=%v err=%v", info, err)
	}
}

// =============================================================================
// WaitForReady integration tests via in-memory pipes
// =============================================================================

func TestWaitForReady_HappyPath(t *testing.T) {
	r, w := io.Pipe()
	go func() {
		_, _ = w.Write([]byte("Starting up...\n"))
		_, _ = w.Write([]byte("CODELENS_STARTING port=8080 host=127.0.0.1\n"))
		_, _ = w.Write([]byte("CODELENS_READY port=8080 host=127.0.0.1 version=1.0.0\n"))
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	info, err := WaitForReady(ctx, r)
	if err != nil {
		t.Fatal(err)
	}
	if info.Port != 8080 {
		t.Errorf("port = %d", info.Port)
	}
}

func TestWaitForReady_StdoutClosedBeforeReadyIsExitedEarly(t *testing.T) {
	r, w := io.Pipe()
	// Close immediately without ever emitting READY (simulates server
	// dying or running ready-signal-only on stderr).
	go func() {
		_, _ = w.Write([]byte("Starting up...\n"))
		_ = w.Close()
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	_, err := WaitForReady(ctx, r)
	if !errors.Is(err, ErrServerExitedEarly) {
		t.Errorf("expected ErrServerExitedEarly; got %v", err)
	}
}

func TestWaitForReady_ContextTimeoutBeatsHangingStdout(t *testing.T) {
	r, _ := io.Pipe() // never written to, never closed
	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()
	_, err := WaitForReady(ctx, r)
	if err != context.DeadlineExceeded {
		t.Errorf("expected deadline exceeded; got %v", err)
	}
}

func TestWaitForReady_ErrorLineFailsImmediately(t *testing.T) {
	r, w := io.Pipe()
	go func() {
		_, _ = w.Write([]byte(`CODELENS_ERROR reason=SCAN message="boom"` + "\n"))
		// Hold stdout open so the test verifies we exit on ERROR, not EOF.
		<-time.After(1 * time.Second)
		_ = w.Close()
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	_, err := WaitForReady(ctx, r)
	var se *ScanError
	if !errors.As(err, &se) {
		t.Fatalf("expected ScanError; got %v", err)
	}
	if se.Reason != "SCAN" || !strings.Contains(se.Message, "boom") {
		t.Errorf("scan err = %+v", se)
	}
}
