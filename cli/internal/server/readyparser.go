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
	// CODELENS_WARNING is an additive, non-fatal advisory line the server emits
	// just before CODELENS_READY (e.g. an uncompiled project with 0 classes).
	// Mirrors reError's `message="..."` shape and its [^"]*] truncation quirk.
	reWarning = regexp.MustCompile(`CODELENS_WARNING message="([^"]*)"`)
)

// ReadyInfo is the result of a successful ready signal.
type ReadyInfo struct {
	Port    int
	Host    string
	Version string
	// Warnings holds any CODELENS_WARNING advisories seen before READY, in
	// emission order. Empty in the common case.
	Warnings []string
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

	// Warnings accumulate across the pre-READY window; the server prints them
	// immediately before CODELENS_READY, so they arrive first and ride out on
	// the returned ReadyInfo. Discarded if the child exits before READY.
	var warnings []string

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
			if w, ok := parseWarning(msg.text); ok {
				warnings = append(warnings, w)
				continue
			}
			if info, err := ParseLine(msg.text); info != nil || err != nil {
				if info != nil {
					info.Warnings = warnings
				}
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

// parseWarning extracts the message from a CODELENS_WARNING line. Returns
// (msg, true) on a match, ("", false) otherwise. Kept separate from ParseLine
// so the READY/ERROR contract there stays untouched.
func parseWarning(line string) (string, bool) {
	if m := reWarning.FindStringSubmatch(line); m != nil {
		return m[1], true
	}
	return "", false
}
