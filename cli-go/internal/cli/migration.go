package cli

import (
	"context"

	"github.com/charliek/codelens/cli-go/internal/client"
	"github.com/spf13/cobra"
)

func newMigrationCmd() *cobra.Command {
	cmd := &cobra.Command{Use: "migration", Short: "Migration complexity analysis"}
	cmd.AddCommand(newMigrationOrderCmd(), newMigrationComplexityCmd())
	return cmd
}

func newMigrationOrderCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "order",
		Short: "Suggested migration order by complexity",
		RunE: func(cmd *cobra.Command, _ []string) error {
			return withRunningServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				return c.GetMigrationOrder(ctx)
			})
		},
	}
}

func newMigrationComplexityCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "complexity <fqn>",
		Short: "Complexity score for a specific class",
		Args:  cobra.RangeArgs(0, 1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return withRunningServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				if len(args) == 0 {
					return c.GetComplexitySummary(ctx)
				}
				return c.GetComplexity(ctx, args[0])
			})
		},
	}
}
