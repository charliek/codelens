package cli

import (
	"context"

	"github.com/charliek/codelens/cli-go/internal/client"
	"github.com/spf13/cobra"
)

func newLintCmd() *cobra.Command {
	cmd := &cobra.Command{Use: "lint", Short: "Kotlin ktlint check/format"}
	cmd.AddCommand(newLintCheckCmd(), newLintFormatCmd())
	return cmd
}

func newLintCheckCmd() *cobra.Command {
	var pattern string
	var includeTests bool
	cmd := &cobra.Command{
		Use:   "check [file]",
		Short: "Check a Kotlin file (or whole project)",
		Args:  cobra.RangeArgs(0, 1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return withRunningServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				if len(args) == 1 {
					return c.LintFile(ctx, args[0])
				}
				return c.LintProject(ctx, pattern, includeTests)
			})
		},
	}
	cmd.Flags().StringVar(&pattern, "pattern", "", "Glob pattern (project mode only)")
	cmd.Flags().BoolVar(&includeTests, "include-tests", true, "Include test sources (project mode only)")
	return cmd
}

func newLintFormatCmd() *cobra.Command {
	var pattern string
	var includeTests, dryRun, writeToFile bool
	cmd := &cobra.Command{
		Use:   "format [file]",
		Short: "Format a Kotlin file (or whole project)",
		Args:  cobra.RangeArgs(0, 1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return withRunningServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				if len(args) == 1 {
					return c.FormatFile(ctx, args[0], writeToFile)
				}
				return c.FormatProject(ctx, pattern, includeTests, dryRun)
			})
		},
	}
	cmd.Flags().StringVar(&pattern, "pattern", "", "Glob pattern (project mode only)")
	cmd.Flags().BoolVar(&includeTests, "include-tests", true, "Include test sources (project mode only)")
	cmd.Flags().BoolVar(&dryRun, "dry-run", false, "Do not write changes (project mode only)")
	cmd.Flags().BoolVar(&writeToFile, "write", false, "Write formatted output back to file (single-file mode only)")
	return cmd
}
