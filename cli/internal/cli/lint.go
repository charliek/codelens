package cli

import (
	"context"

	"github.com/charliek/codelens/cli/internal/client"
	clierrors "github.com/charliek/codelens/cli/internal/errors"
	"github.com/charliek/codelens/cli/internal/render"
	"github.com/spf13/cobra"
)

func newLintCmd() *cobra.Command {
	cmd := &cobra.Command{Use: "lint", Short: "Kotlin ktlint check/format", GroupID: "tools"}
	cmd.AddCommand(newLintCheckCmd(), newLintFormatCmd())
	return cmd
}

// newLintCheckCmd: `codelens lint check [file]` — exits 1 when any
// violations are found, matching the documented Python behavior
// (cli/src/codelens_cli/commands/lint.py:54,72-73,82-84).
func newLintCheckCmd() *cobra.Command {
	var pattern string
	var includeTests bool
	cmd := &cobra.Command{
		Use:   "check [file]",
		Short: "Check a Kotlin file (or whole project) for style violations",
		Args:  cobra.RangeArgs(0, 1),
		RunE: func(cmd *cobra.Command, args []string) error {
			result, err := runWithServer(func(ctx context.Context, c *client.Client) (any, error) {
				if len(args) == 1 {
					return c.LintFile(ctx, args[0])
				}
				return c.LintProject(ctx, pattern, includeTests)
			})
			if err != nil {
				return err
			}
			if emitErr := emit(cmd, result, render.LintCheck); emitErr != nil {
				return emitErr
			}
			// Drive exit 1 when violations were found. Empty message
			// suppresses a duplicate "Error:" line — the JSON output is
			// the user-visible signal already.
			switch r := result.(type) {
			case *client.LintFileResponse:
				if r != nil && r.ErrorCount > 0 {
					return clierrors.New(clierrors.GeneralError, "")
				}
			case *client.LintProjectResponse:
				if r != nil && r.TotalErrorCount > 0 {
					return clierrors.New(clierrors.GeneralError, "")
				}
			}
			return nil
		},
	}
	cmd.Flags().StringVar(&pattern, "pattern", "", "Glob pattern (project mode only)")
	cmd.Flags().BoolVar(&includeTests, "include-tests", true, "Include test sources (project mode only)")
	return cmd
}

// newLintFormatCmd: `codelens lint format [file]` — writes formatted
// output back to the file by default. Use --dry-run to preview without
// modifying. Mirrors Python's `write_to_file=not dry_run`
// (cli/src/codelens_cli/commands/lint.py:122).
func newLintFormatCmd() *cobra.Command {
	var pattern string
	var includeTests, dryRun bool
	cmd := &cobra.Command{
		Use:   "format [file]",
		Short: "Format a Kotlin file (or whole project); writes by default unless --dry-run",
		Args:  cobra.RangeArgs(0, 1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return withRenderedServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				if len(args) == 1 {
					return c.FormatFile(ctx, args[0], !dryRun)
				}
				return c.FormatProject(ctx, pattern, includeTests, dryRun)
			}, render.LintFormat)
		},
	}
	cmd.Flags().StringVar(&pattern, "pattern", "", "Glob pattern (project mode only)")
	cmd.Flags().BoolVar(&includeTests, "include-tests", true, "Include test sources (project mode only)")
	cmd.Flags().BoolVarP(&dryRun, "dry-run", "n", false, "Show changes without writing them")
	return cmd
}
