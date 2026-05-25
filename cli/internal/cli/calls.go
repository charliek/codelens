package cli

import (
	"context"

	"github.com/charliek/codelens/cli/internal/client"
	"github.com/spf13/cobra"
)

// newCallsCmd is `codelens calls <fqn>` — the forward call-site primitive. It
// returns the raw invocations a class's methods make (owner type, method name,
// descriptor, constant args, line number) straight from bytecode.
func newCallsCmd() *cobra.Command {
	var method, descriptor string
	cmd := &cobra.Command{
		Use:   "calls <fqn>",
		Short: "Show the calls a class's methods make (raw bytecode call sites)",
		Long: "Extract, from bytecode, every invocation a class's methods make — owner type, " +
			"method name, descriptor, any constant string/number/class arguments, and source line. " +
			"Use --method to scope to one method. Returns raw facts; the caller interprets them.",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return withRunningServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				return c.GetCalls(ctx, args[0], method, descriptor)
			})
		},
	}
	cmd.Flags().StringVarP(&method, "method", "m", "", "Only show calls made by this method")
	cmd.Flags().StringVar(&descriptor, "descriptor", "", "JVM descriptor to disambiguate overloads (use with --method)")
	return cmd
}
