// Package server orchestrates the lifecycle of a CodeLens server child
// process. The wire contract with the server is locked: stdout lines of
// CODELENS_STARTING / CODELENS_READY / CODELENS_ERROR, parsed via the
// regexes below.
package server

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"io"
	"regexp"
)

// Compile at package init — locked behavior from settings.py:317-320.
var (
	reReady = regexp.MustCompile(`CODELENS_READY port=(\d+) host=(\S+) version=(\S+)`)
	reError = regexp.MustCompile(`CODELENS_ERROR reason=(\S+) message="([^"]*)"`)
)

// ReadyInfo is the result of a successful ready signal.
type ReadyInfo struct {
	Port    int
	Host    string
	Version string
}

// ErrServerExitedEarly is returned if the child process exits before
// emitting CODELENS_READY.
var ErrServerExitedEarly = errors.New("server process exited before ready")

// ScanError is returned when CODELENS_ERROR is observed. Carries the parsed
// reason and message.
type ScanError struct {
	Reason  string
	Message string
}

func (e *ScanError) Error() string {
	return fmt.Sprintf("server scan failed (%s): %s", e.Reason, e.Message)
}

// WaitForReady reads `stdout` line-by-line under a context-based timeout
// and returns when it sees the CODELENS_READY signal. CODELENS_STARTING is
// ignored, CODELENS_ERROR fails immediately, and an EOF on stdout (which
// the caller should arrange to happen when the child exits) raises
// ErrServerExitedEarly.
//
// `stdout` is a reader over the child's stdout pipe. Reading happens on a
// background goroutine so a stuck child doesn't block past the context
// deadline.
func WaitForReady(ctx context.Context, stdout io.Reader) (*ReadyInfo, error) {
	type lineMsg struct {
		text string
		eof  bool
	}
	lines := make(chan lineMsg, 16)
	go func() {
		defer close(lines)
		scanner := bufio.NewScanner(stdout)
		scanner.Buffer(make([]byte, 64*1024), 1<<20)
		for scanner.Scan() {
			lines <- lineMsg{text: scanner.Text()}
		}
		lines <- lineMsg{eof: true}
	}()

	for {
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case msg, ok := <-lines:
			if !ok {
				return nil, ErrServerExitedEarly
			}
			if msg.eof {
				return nil, ErrServerExitedEarly
			}
			if info, err := ParseLine(msg.text); info != nil || err != nil {
				return info, err
			}
		}
	}
}

// ParseLine applies the regexes to a single stdout line. Returns (info, nil)
// on READY, (nil, *ScanError) on ERROR, or (nil, nil) for any other line
// (including CODELENS_STARTING).
//
// Python evaluates ready_pattern.search FIRST in settings.py:338-345 — so if
// a single line somehow contained both READY and ERROR, READY wins.
func ParseLine(line string) (*ReadyInfo, error) {
	if m := reReady.FindStringSubmatch(line); m != nil {
		var port int
		_, _ = fmt.Sscanf(m[1], "%d", &port)
		return &ReadyInfo{Port: port, Host: m[2], Version: m[3]}, nil
	}
	if m := reError.FindStringSubmatch(line); m != nil {
		return nil, &ScanError{Reason: m[1], Message: m[2]}
	}
	return nil, nil
}
