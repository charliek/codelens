package cli

import (
	"context"

	"github.com/charliek/codelens/cli/internal/client"
	"github.com/spf13/cobra"
)

func newModulesCmd() *cobra.Command {
	cmd := &cobra.Command{Use: "modules", Short: "Guice module analysis"}
	cmd.AddCommand(newModulesListCmd(), newModulesShowCmd(), newModulesBindingsCmd())
	return cmd
}

func newModulesListCmd() *cobra.Command {
	var includeLibraries bool
	cmd := &cobra.Command{
		Use:   "list",
		Short: "List Guice modules",
		RunE: func(cmd *cobra.Command, _ []string) error {
			return withRunningServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				return c.ListModules(ctx, includeLibraries)
			})
		},
	}
	cmd.Flags().BoolVarP(&includeLibraries, "include-libraries", "L", false, "Include library classes")
	return cmd
}

func newModulesShowCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "show <fqn>",
		Short: "Show module details",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return withRunningServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				return c.GetModule(ctx, args[0])
			})
		},
	}
}

func newModulesBindingsCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "bindings <fqn>",
		Short: "Find bindings for a type",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return withRunningServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				return c.GetBindings(ctx, args[0])
			})
		},
	}
}
