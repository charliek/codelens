package cli

import (
	"context"

	"github.com/charliek/codelens/cli/internal/client"
	"github.com/spf13/cobra"
)

func newRoutesCmd() *cobra.Command {
	cmd := &cobra.Command{Use: "routes", Short: "Route analysis"}
	// Python names locked: list/tree/spring.
	cmd.AddCommand(newRoutesListCmd(), newRoutesTreeCmd(), newRoutesSpringCmd())
	return cmd
}

func newRoutesListCmd() *cobra.Command {
	var includeLibraries bool
	cmd := &cobra.Command{
		Use:   "list",
		Short: "List all routes",
		RunE: func(cmd *cobra.Command, _ []string) error {
			return withRunningServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				return c.GetRoutes(ctx, includeLibraries)
			})
		},
	}
	cmd.Flags().BoolVarP(&includeLibraries, "include-libraries", "L", false, "Include library classes")
	return cmd
}

func newRoutesTreeCmd() *cobra.Command {
	var includeLibraries bool
	cmd := &cobra.Command{
		Use:   "tree",
		Short: "Route tree structure",
		RunE: func(cmd *cobra.Command, _ []string) error {
			return withRunningServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				return c.GetRouteTree(ctx, includeLibraries)
			})
		},
	}
	cmd.Flags().BoolVarP(&includeLibraries, "include-libraries", "L", false, "Include library classes")
	return cmd
}

func newRoutesSpringCmd() *cobra.Command {
	var includeLibraries bool
	cmd := &cobra.Command{
		Use:   "spring",
		Short: "Spring @RequestMapping equivalents",
		RunE: func(cmd *cobra.Command, _ []string) error {
			return withRunningServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				return c.GetSpringMappings(ctx, includeLibraries)
			})
		},
	}
	cmd.Flags().BoolVarP(&includeLibraries, "include-libraries", "L", false, "Include library classes")
	return cmd
}
