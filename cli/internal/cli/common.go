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
	renderpkg "github.com/charliek/codelens/cli/internal/render"
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

// renderFunc renders a fetched result as human-readable table output to w. It
// receives the same value runWithServer returns (json.RawMessage, []byte for
// DOT, or a typed struct), so renderers cast as needed. Returning
// render.ErrFallback means "no sensible table — emit JSON instead". The
// render.* functions satisfy this type structurally.
type renderFunc func(w io.Writer, v any) error

// withRenderedServer is the standard preamble for an analysis command: resolve
// project, auto-start the server if needed, call fetch, then emit the result —
// as a table (via render) on a TTY or as JSON when piped / forced with --json.
//
// Use this for commands that don't need to react to the response (no exit code
// based on response content). Commands that need that should call runWithServer
// directly and then call emit.
func withRenderedServer(cmd *cobra.Command, fetch analysisFunc, render renderFunc) error {
	result, err := runWithServer(fetch)
	if err != nil {
		return err
	}
	return emit(cmd, result, render)
}

// emit is the single output-dispatch point. It resolves the output mode once
// and either prints JSON (byte-identical to the pre-table behavior) or runs the
// renderer, falling back to JSON when the renderer returns render.ErrFallback
// (e.g. DOT bytes, an empty graph, or an unparseable payload).
func emit(cmd *cobra.Command, v any, render renderFunc) error {
	w := cmd.OutOrStdout()
	if render == nil || resolveMode() == modeJSON {
		return emitAnalysisResultTo(w, v)
	}
	if err := render(w, v); err != nil {
		if errors.Is(err, renderpkg.ErrFallback) {
			return emitAnalysisResultTo(w, v)
		}
		return err
	}
	return nil
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

// emitLifecycle emits a locally-computed lifecycle result. JSON mode (or a
// non-TTY) uses the JSON path with jsonValue, byte-identical to before tables
// existed; table mode runs table. Unlike emit, table takes no value because
// lifecycle commands already hold typed data and render it directly.
func emitLifecycle(cmd *cobra.Command, jsonValue any, table func(w io.Writer) error) error {
	w := cmd.OutOrStdout()
	if resolveMode() == modeJSON {
		return emitAnalysisResultTo(w, jsonValue)
	}
	return table(w)
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
