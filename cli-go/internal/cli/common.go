package cli

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/charliek/codelens/cli-go/internal/client"
	clierrors "github.com/charliek/codelens/cli-go/internal/errors"
	"github.com/charliek/codelens/cli-go/internal/output"
	"github.com/charliek/codelens/cli-go/internal/server"
	"github.com/charliek/codelens/cli-go/internal/state"
	"github.com/spf13/cobra"
)

// dataClientFactory builds an HTTP client to talk to a running server.
// Tests replace this to capture/mock requests.
var dataClientFactory = client.NewClient

// withRunningServer is the standard preamble for any analysis command. It
// resolves the project path, ensures a server is running (auto-starting if
// not), and hands the caller a Client.
//
// fn is called with a Client wired to the running server. Its return value
// (a json.RawMessage or []byte) is emitted as JSON to cmd.OutOrStdout().
type analysisFunc func(ctx context.Context, c *client.Client) (any, error)

func withRunningServer(cmd *cobra.Command, fn analysisFunc) error {
	projectPath, err := resolveProjectPath(flagProject)
	if err != nil {
		return err
	}
	svc, err := lifecycleFactory()
	if err != nil {
		return clierrors.New(clierrors.ServerError, "%v", err)
	}

	st, err := svc.Find(projectPath)
	if err != nil {
		return clierrors.New(clierrors.ServerError, "%v", err)
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
				return clierrors.New(clierrors.Timeout, "server did not start within 180s")
			}
			return clierrors.New(clierrors.ServerError, "%v", err)
		}
	}

	cli := dataClientFactory(st.Host, st.Port)
	defer cli.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	result, err := fn(ctx, cli)
	if err != nil {
		return apiErrorToExit(err)
	}
	return emitAnalysisResult(cmd, result)
}

func emitAnalysisResult(cmd *cobra.Command, v any) error {
	switch r := v.(type) {
	case json.RawMessage:
		return output.PrintRawJSON(cmd.OutOrStdout(), r)
	case []byte:
		// Raw bytes (DOT). Mirror Python's typer.echo, which always appends
		// a trailing newline even if the input already ends in one.
		out := append(append([]byte{}, r...), '\n')
		_, err := cmd.OutOrStdout().Write(out)
		return err
	default:
		return output.PrintJSON(cmd.OutOrStdout(), v)
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
