package cli

import (
	"context"

	"github.com/charliek/codelens/cli-go/internal/client"
	"github.com/spf13/cobra"
)

func newDepsCmd() *cobra.Command {
	cmd := &cobra.Command{Use: "deps", Short: "Dependency analysis"}
	// Python names locked: graph/foundation/quickwins (one word).
	cmd.AddCommand(newDepsGraphCmd(), newDepsFoundationCmd(), newDepsQuickwinsCmd())
	return cmd
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
