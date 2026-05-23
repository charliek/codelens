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
	// Python uses Typer's --foo/--no-foo paired boolean convention. We mirror
	// that with two separate flags: --blocking sets *true, --no-blocking sets
	// *false, neither leaves the filter omitted. Only one of the pair should
	// be set at once; if both are passed the last one to be set wins.
	var blocking, noBlocking, async, noAsync, fork, noFork bool
	cmd := &cobra.Command{
		Use:   "search",
		Short: "Find handlers with specific Promise patterns",
		RunE: func(cmd *cobra.Command, _ []string) error {
			f.UsesBlocking = pairedBool(cmd, "blocking", "no-blocking", blocking, noBlocking)
			f.UsesAsync = pairedBool(cmd, "async", "no-async", async, noAsync)
			f.UsesFork = pairedBool(cmd, "fork", "no-fork", fork, noFork)
			return withRunningServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				return c.SearchPromises(ctx, f)
			})
		},
	}
	cmd.Flags().BoolVar(&blocking, "blocking", false, "Filter by Blocking usage")
	cmd.Flags().BoolVar(&noBlocking, "no-blocking", false, "Filter to handlers NOT using Blocking")
	cmd.Flags().BoolVar(&async, "async", false, "Filter by async usage")
	cmd.Flags().BoolVar(&noAsync, "no-async", false, "Filter to handlers NOT using async")
	cmd.Flags().BoolVar(&fork, "fork", false, "Filter by fork usage")
	cmd.Flags().BoolVar(&noFork, "no-fork", false, "Filter to handlers NOT using fork")
	// Python flag name: --min-ops (not --min-operations).
	cmd.Flags().IntVar(&f.MinOperations, "min-ops", 0, "Minimum number of Promise operations")
	return cmd
}

// pairedBool returns a tri-state from a --flag / --no-flag pair. If neither
// was set on the command line, returns nil (omit from request).
func pairedBool(cmd *cobra.Command, posName, negName string, pos, neg bool) *bool {
	posSet := cmd.Flags().Changed(posName)
	negSet := cmd.Flags().Changed(negName)
	switch {
	case posSet && pos:
		t := true
		return &t
	case negSet && neg:
		f := false
		return &f
	case posSet:
		// --flag explicitly false (e.g. user typed `--flag=false`)
		t := false
		return &t
	case negSet:
		t := true
		return &t
	}
	return nil
}
