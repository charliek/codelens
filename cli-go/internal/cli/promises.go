package cli

import (
	"context"

	"github.com/charliek/codelens/cli-go/internal/client"
	"github.com/spf13/cobra"
)

func newPromisesCmd() *cobra.Command {
	cmd := &cobra.Command{Use: "promises", Short: "Ratpack Promise usage analysis"}
	cmd.AddCommand(newPromisesSummaryCmd(), newPromisesShowCmd(), newPromisesSearchCmd())
	return cmd
}

func newPromisesSummaryCmd() *cobra.Command {
	var includeLibraries bool
	cmd := &cobra.Command{
		Use:   "summary",
		Short: "Project-wide Promise usage summary",
		RunE: func(cmd *cobra.Command, _ []string) error {
			return withRunningServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				return c.GetPromiseSummary(ctx, includeLibraries)
			})
		},
	}
	cmd.Flags().BoolVarP(&includeLibraries, "include-libraries", "L", false, "Include library classes")
	return cmd
}

func newPromisesShowCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "show <fqn>",
		Short: "Promise usage for a specific class",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return withRunningServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				return c.GetPromiseUsage(ctx, args[0])
			})
		},
	}
}

func newPromisesSearchCmd() *cobra.Command {
	var f client.SearchPromisesFilter
	var blockingFlag, asyncFlag, forkFlag string
	cmd := &cobra.Command{
		Use:   "search",
		Short: "Find handlers with specific Promise patterns",
		RunE: func(cmd *cobra.Command, _ []string) error {
			f.UsesBlocking = parseTriBool(blockingFlag)
			f.UsesAsync = parseTriBool(asyncFlag)
			f.UsesFork = parseTriBool(forkFlag)
			return withRunningServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				return c.SearchPromises(ctx, f)
			})
		},
	}
	// String flags so the user can pass "true"/"false"/unset to drive the
	// tri-state semantics. Unset → omit, "true"/"false" → send literal.
	cmd.Flags().StringVar(&blockingFlag, "uses-blocking", "", "true|false (omit to skip filter)")
	cmd.Flags().StringVar(&asyncFlag, "uses-async", "", "true|false (omit to skip filter)")
	cmd.Flags().StringVar(&forkFlag, "uses-fork", "", "true|false (omit to skip filter)")
	cmd.Flags().IntVar(&f.MinOperations, "min-operations", 0, "Minimum number of Promise operations")
	return cmd
}

func parseTriBool(s string) *bool {
	switch s {
	case "true", "True", "TRUE":
		t := true
		return &t
	case "false", "False", "FALSE":
		f := false
		return &f
	}
	return nil
}
