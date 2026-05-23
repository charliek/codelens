package cli

import (
	"context"

	"github.com/charliek/codelens/cli-go/internal/client"
	"github.com/spf13/cobra"
)

func newHandlersCmd() *cobra.Command {
	cmd := &cobra.Command{Use: "handlers", Short: "Ratpack handler analysis"}
	cmd.AddCommand(newHandlersListCmd(), newHandlersShowCmd())
	return cmd
}

func newHandlersListCmd() *cobra.Command {
	var handlerType, tier string
	var includeLibraries bool
	cmd := &cobra.Command{
		Use:   "list",
		Short: "List Ratpack handlers",
		RunE: func(cmd *cobra.Command, _ []string) error {
			return withRunningServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				return c.ListHandlers(ctx, handlerType, tier, includeLibraries)
			})
		},
	}
	cmd.Flags().StringVar(&handlerType, "type", "", "Filter by handler type")
	cmd.Flags().StringVar(&tier, "tier", "", "Filter by complexity tier (LOW/MEDIUM/HIGH/CRITICAL)")
	cmd.Flags().BoolVarP(&includeLibraries, "include-libraries", "L", false, "Include library classes")
	return cmd
}

func newHandlersShowCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "show <fqn>",
		Short: "Show handler details with migration notes",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return withRunningServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				return c.GetHandler(ctx, args[0])
			})
		},
	}
}
