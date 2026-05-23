package cli

import (
	"context"

	"github.com/charliek/codelens/cli/internal/client"
	"github.com/spf13/cobra"
)

func newIntegrationsCmd() *cobra.Command {
	cmd := &cobra.Command{Use: "integrations", Short: "Integration detection"}
	cmd.AddCommand(newIntegrationsListCmd(), newIntegrationsShowCmd(), newIntegrationsFindCmd())
	return cmd
}

// Python names locked: list/show/find (NOT summary/show/by-type).
// See cli/src/codelens_cli/commands/integrations.py:157.

func newIntegrationsListCmd() *cobra.Command {
	var integrationType, subType string
	var includeLibraries bool
	cmd := &cobra.Command{
		Use:   "list",
		Short: "Project-wide integration summary",
		RunE: func(cmd *cobra.Command, _ []string) error {
			return withRunningServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				return c.ListIntegrations(ctx, integrationType, subType, includeLibraries)
			})
		},
	}
	cmd.Flags().StringVar(&integrationType, "type", "", "Filter by integration type (HTTP_CLIENT, DATABASE, ...)")
	cmd.Flags().StringVar(&subType, "sub-type", "", "Filter by sub-type")
	cmd.Flags().BoolVarP(&includeLibraries, "include-libraries", "L", false, "Include library classes")
	return cmd
}

func newIntegrationsShowCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "show <fqn>",
		Short: "Integrations for a specific class",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return withRunningServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				return c.GetClassIntegrations(ctx, args[0])
			})
		},
	}
}

func newIntegrationsFindCmd() *cobra.Command {
	var subType string
	var includeLibraries bool
	cmd := &cobra.Command{
		Use:   "find <integration-type>",
		Short: "Find classes by integration type",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return withRunningServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				return c.FindIntegrationsByType(ctx, args[0], subType, includeLibraries)
			})
		},
	}
	cmd.Flags().StringVar(&subType, "sub-type", "", "Optional sub-type filter")
	cmd.Flags().BoolVarP(&includeLibraries, "include-libraries", "L", false, "Include library classes")
	return cmd
}
