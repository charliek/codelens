package cli

import (
	"context"
	"os"

	"github.com/charliek/codelens/cli/internal/client"
	clierrors "github.com/charliek/codelens/cli/internal/errors"
	"github.com/charliek/codelens/cli/internal/render"
	"github.com/spf13/cobra"
)

// newDepsCmd is `codelens deps` — the project-wide dependency graph. Runnable
// on its own (emits the graph, like `deps graph`) and a parent of the
// `graph` and `foundation` subcommands.
func newDepsCmd() *cobra.Command {
	var format, outputPath string
	cmd := &cobra.Command{
		Use:   "deps",
		Short: "Project-wide dependency graph (no args: the full graph)",
		// When a subcommand is invoked, Cobra dispatches to it and this RunE
		// is skipped. Without args, emit the graph.
		RunE: func(cmd *cobra.Command, _ []string) error {
			result, err := runWithServer(func(ctx context.Context, c *client.Client) (any, error) {
				return c.GetGraph(ctx, format)
			})
			if err != nil {
				return err
			}
			if outputPath != "" {
				return writeOutput(outputPath, result)
			}
			return emit(cmd, result, render.DepsGraph)
		},
	}
	cmd.Flags().StringVarP(&format, "format", "f", "json", "Output format: json | dot")
	cmd.Flags().StringVarP(&outputPath, "output", "o", "", "Write to this file instead of stdout (for dot/json)")

	cmd.AddCommand(newDepsGraphCmd(), newDepsFoundationCmd())
	return cmd
}

// writeOutput dumps the result to the given file path. Used by
// `codelens deps --output ...` for both DOT and JSON.
func writeOutput(path string, result any) error {
	switch r := result.(type) {
	case []byte:
		return os.WriteFile(path, r, 0o644)
	default:
		f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o644)
		if err != nil {
			return clierrors.New(clierrors.GeneralError, "write %s: %v", path, err)
		}
		defer func() { _ = f.Close() }()
		return emitAnalysisResultTo(f, result)
	}
}

func newDepsGraphCmd() *cobra.Command {
	var format string
	cmd := &cobra.Command{
		Use:   "graph",
		Short: "Project-wide dependency graph",
		RunE: func(cmd *cobra.Command, _ []string) error {
			return withRenderedServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				return c.GetGraph(ctx, format)
			}, render.DepsGraph)
		},
	}
	// dot output bypasses the JSON path; the wrapper sees a []byte and writes
	// it verbatim.
	cmd.Flags().StringVar(&format, "format", "json", "Output format: json | dot")
	return cmd
}

func newDepsFoundationCmd() *cobra.Command {
	var minDependents int
	cmd := &cobra.Command{
		Use:   "foundation",
		Short: "Most depended-on classes (by in-degree)",
		RunE: func(cmd *cobra.Command, _ []string) error {
			return withRenderedServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				return c.GetFoundation(ctx, minDependents)
			}, render.Foundation)
		},
	}
	cmd.Flags().IntVar(&minDependents, "min-dependents", 2, "Minimum number of dependents to qualify")
	return cmd
}
