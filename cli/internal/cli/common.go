package cli

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"time"

	"github.com/charliek/codelens/cli/internal/client"
	clierrors "github.com/charliek/codelens/cli/internal/errors"
	"github.com/charliek/codelens/cli/internal/output"
	"github.com/charliek/codelens/cli/internal/server"
	"github.com/charliek/codelens/cli/internal/state"
	"github.com/spf13/cobra"
)

// dataClientFactory builds an HTTP client to talk to a running server.
// Tests replace this to capture/mock requests.
var dataClientFactory = client.NewClient

// analysisFunc is called with a Client wired to the running server. Its
// return value (typically a json.RawMessage, []byte for DOT, or a typed
// struct) is emitted as JSON / raw bytes to cmd.OutOrStdout().
type analysisFunc func(ctx context.Context, c *client.Client) (any, error)

// withRunningServer is the standard preamble for any analysis command:
// resolve project, auto-start the server if needed, call fn, emit result.
//
// Use this for commands that don't need to react to the response (no exit
// code based on response content, no client-side filtering). Commands that
// need that should call runWithServer directly.
func withRunningServer(cmd *cobra.Command, fn analysisFunc) error {
	result, err := runWithServer(fn)
	if err != nil {
		return err
	}
	return emitAnalysisResult(cmd, result)
}

// runWithServer handles project resolution + server auto-start + the
// client call, returning the raw result so callers can inspect a typed
// response (e.g. lint commands need ErrorCount to drive the exit code).
func runWithServer(fn analysisFunc) (any, error) {
	projectPath, err := resolveProjectPath(flagProject)
	if err != nil {
		return nil, err
	}
	svc, err := lifecycleFactory()
	if err != nil {
		return nil, clierrors.New(clierrors.ServerError, "%v", err)
	}

	st, err := svc.Find(projectPath)
	if err != nil {
		return nil, clierrors.New(clierrors.ServerError, "%v", err)
	}
	if st == nil || st.Status != state.StatusReady {
		ctx, cancel := context.WithTimeout(context.Background(), 180*time.Second)
		st, err = svc.Start(ctx, server.StartOptions{
			ProjectPath: projectPath,
			Timeout:     180 * time.Second,
		})
		cancel()
		if err != nil {
			if errors.Is(err, context.DeadlineExceeded) {
				return nil, clierrors.New(clierrors.Timeout, "server did not start within 180s")
			}
			return nil, clierrors.New(clierrors.ServerError, "%v", err)
		}
	}

	cli := dataClientFactory(st.Host, st.Port)
	defer cli.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	result, err := fn(ctx, cli)
	if err != nil {
		return nil, apiErrorToExit(err)
	}
	return result, nil
}

func emitAnalysisResult(cmd *cobra.Command, v any) error {
	return emitAnalysisResultTo(cmd.OutOrStdout(), v)
}

func emitAnalysisResultTo(w io.Writer, v any) error {
	switch r := v.(type) {
	case json.RawMessage:
		return output.PrintRawJSON(w, r)
	case []byte:
		// Raw bytes (DOT). Mirror Python's typer.echo, which always appends
		// a trailing newline even if the input already ends in one.
		out := append(append([]byte{}, r...), '\n')
		_, err := w.Write(out)
		return err
	default:
		return output.PrintJSON(w, v)
	}
}

// apiErrorToExit maps client errors to typed exit codes. Mirrors Python
// commands/common.py:42-56.
func apiErrorToExit(err error) error {
	var httpErr *client.HTTPError
	if errors.As(err, &httpErr) {
		switch {
		case httpErr.Status == 404:
			return clierrors.New(clierrors.GeneralError, "not found: %s", httpErr.Body)
		case httpErr.Status >= 500:
			return clierrors.New(clierrors.ServerError, "server error (%d): %s", httpErr.Status, httpErr.Body)
		default:
			return clierrors.New(clierrors.GeneralError, "%s", httpErr.Body)
		}
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return clierrors.New(clierrors.Timeout, "request timed out")
	}
	// Network unreachable / connection refused / DNS errors → 6.
	if isConnectError(err) {
		return clierrors.New(clierrors.ConnectionError, "could not connect to server: %v", err)
	}
	return clierrors.New(clierrors.GeneralError, "%v", err)
}

func isConnectError(err error) bool {
	if err == nil {
		return false
	}
	msg := err.Error()
	for _, needle := range []string{"connection refused", "no such host", "dial", "EOF"} {
		if contains(msg, needle) {
			return true
		}
	}
	return false
}

func contains(s, sub string) bool {
	return len(s) >= len(sub) && (s == sub || (len(s) > len(sub) && (s[:len(sub)] == sub || s[len(s)-len(sub):] == sub || indexOf(s, sub) >= 0)))
}

func indexOf(s, sub string) int {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return i
		}
	}
	return -1
}

// Compile-time check that *cobra.Command has an OutOrStdout method we use.
var _ = (*cobra.Command)(nil).OutOrStdout

// silence unused import warning when none of the helpers use fmt directly.
var _ = fmt.Errorf
