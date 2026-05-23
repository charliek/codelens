package cli

import (
	"context"
	"os"

	"github.com/charliek/codelens/cli-go/internal/client"
	clierrors "github.com/charliek/codelens/cli-go/internal/errors"
	"github.com/spf13/cobra"
)

// newDepsCmd is `codelens deps` — runnable on its own (hits
// /api/v1/ratpack/dependencies) and also a parent of graph/foundation/
// quickwins. Mirrors Python's `@app.callback(invoke_without_command=True)`
// pattern in cli/src/codelens_cli/commands/deps.py:217-269.
func newDepsCmd() *cobra.Command {
	var format, outputPath string
	cmd := &cobra.Command{
		Use:   "deps",
		Short: "Dependency analysis (no args: full project summary)",
		// When a subcommand is invoked, Cobra dispatches to it directly and
		// this RunE is skipped. Without args, we run the default action.
		RunE: func(cmd *cobra.Command, _ []string) error {
			result, err := runWithServer(func(ctx context.Context, c *client.Client) (any, error) {
				return c.GetDependencyAnalysis(ctx, format)
			})
			if err != nil {
				return err
			}
			// --output writes to file instead of stdout.
			if outputPath != "" {
				return writeOutput(outputPath, result)
			}
			return emitAnalysisResult(cmd, result)
		},
	}
	cmd.Flags().StringVarP(&format, "format", "f", "json", "Output format: json | dot")
	cmd.Flags().StringVarP(&outputPath, "output", "o", "", "Write to this file instead of stdout (for dot/json)")

	// Subcommands.
	cmd.AddCommand(newDepsGraphCmd(), newDepsFoundationCmd(), newDepsQuickwinsCmd())
	return cmd
}

// writeOutput dumps the analysis result to the given file path. Used by
// `codelens deps --output ...`. Mirrors Python's Path(output).write_text
// fallback for both DOT and JSON paths.
func writeOutput(path string, result any) error {
	switch r := result.(type) {
	case []byte:
		return os.WriteFile(path, r, 0o644)
	default:
		// json.RawMessage / structs go through PrintRawJSON-style indent.
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
		Short: "Full dependency graph",
		RunE: func(cmd *cobra.Command, _ []string) error {
			return withRunningServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				return c.GetDependencyGraph(ctx, format)
			})
		},
	}
	// dot output bypasses the JSON path; the wrapper sees a []byte and
	// writes it verbatim. Locked exception from commands/deps.py:249,331.
	cmd.Flags().StringVar(&format, "format", "json", "Output format: json | dot")
	return cmd
}

func newDepsFoundationCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "foundation",
		Short: "Most depended-on classes",
		RunE: func(cmd *cobra.Command, _ []string) error {
			return withRunningServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				return c.GetFoundationClasses(ctx)
			})
		},
	}
}

func newDepsQuickwinsCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "quickwins",
		Short: "Low-complexity, low-dependency handlers",
		RunE: func(cmd *cobra.Command, _ []string) error {
			return withRunningServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				return c.GetQuickWins(ctx)
			})
		},
	}
}
