package cli

import (
	"context"

	"github.com/charliek/codelens/cli-go/internal/client"
	"github.com/spf13/cobra"
)

func newAntipatternsCmd() *cobra.Command {
	cmd := &cobra.Command{Use: "antipatterns", Short: "Anti-pattern detection"}
	// Python names locked: scan/show (NOT summary/show).
	cmd.AddCommand(newAntipatternsScanCmd(), newAntipatternsShowCmd())
	return cmd
}

func newAntipatternsScanCmd() *cobra.Command {
	var severity, patternType string
	var includeLibraries bool
	cmd := &cobra.Command{
		Use:   "scan",
		Short: "Project-wide anti-pattern summary",
		RunE: func(cmd *cobra.Command, _ []string) error {
			return withRunningServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				return c.GetAntipatterns(ctx, severity, patternType, includeLibraries)
			})
		},
	}
	cmd.Flags().StringVar(&severity, "severity", "", "Filter by severity (INFO/WARNING/ERROR/CRITICAL)")
	cmd.Flags().StringVar(&patternType, "type", "", "Filter by anti-pattern type")
	cmd.Flags().BoolVarP(&includeLibraries, "include-libraries", "L", false, "Include library classes")
	return cmd
}

func newAntipatternsShowCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "show <fqn>",
		Short: "Anti-patterns for a specific class",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return withRunningServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				return c.GetClassAntipatterns(ctx, args[0])
			})
		},
	}
}
