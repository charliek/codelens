package cli

import (
	"context"
	"fmt"

	"github.com/charliek/codelens/cli/internal/client"
	"github.com/charliek/codelens/cli/internal/render"
	"github.com/spf13/cobra"
)

// newCallsCmd is `codelens calls <fqn>` — the forward call-site primitive. It
// returns the raw invocations a class's methods make (owner type, method name,
// descriptor, constant args, line number) straight from bytecode.
func newCallsCmd() *cobra.Command {
	var method, descriptor, inMethodsReturning, inMethodsAnnotated string
	cmd := &cobra.Command{
		Use:     "calls <fqn>",
		Short:   "Show the calls a class's methods make (raw bytecode call sites)",
		GroupID: "analyze",
		Long: "Extract, from bytecode, every invocation a class's methods make — owner type, " +
			"method name, descriptor, any constant string/number/class arguments, and source line. " +
			"Use --method to scope to one method. Use --in-methods-returning / --in-methods-annotated " +
			"to keep only call-sites inside methods that return a given type or carry a given annotation " +
			"(e.g. blocking calls inside reactive Mono/Flux handlers). Returns raw facts; the caller " +
			"interprets them.",
		Args: func(cmd *cobra.Command, args []string) error {
			if err := cobra.ExactArgs(1)(cmd, args); err != nil {
				return err
			}
			// --descriptor only disambiguates a named method; reject the
			// combination instead of silently ignoring it.
			if descriptor != "" && method == "" {
				return fmt.Errorf("invalid argument: --descriptor requires --method")
			}
			return nil
		},
		RunE: func(cmd *cobra.Command, args []string) error {
			return withRenderedServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				return c.GetCalls(ctx, args[0], method, descriptor, inMethodsReturning, inMethodsAnnotated)
			}, render.Calls)
		},
	}
	cmd.Flags().StringVarP(&method, "method", "m", "", "Only show calls made by this method")
	cmd.Flags().StringVar(&descriptor, "descriptor", "", "JVM descriptor to disambiguate overloads (use with --method)")
	cmd.Flags().StringVar(&inMethodsReturning, "in-methods-returning", "",
		"Only call-sites inside methods returning this type (FQN), e.g. reactor.core.publisher.Mono")
	cmd.Flags().StringVar(&inMethodsAnnotated, "in-methods-annotated", "",
		"Only call-sites inside methods annotated with this type (FQN)")
	return cmd
}
