package cli

import (
	"context"
	"strings"

	"github.com/charliek/codelens/cli/internal/client"
	"github.com/charliek/codelens/cli/internal/render"
	"github.com/spf13/cobra"
)

func newSourceCmd() *cobra.Command {
	cmd := &cobra.Command{Use: "source", Short: "Source code retrieval"}
	cmd.AddCommand(newSourceShowCmd(), newSourceMethodCmd())
	return cmd
}

func newSourceShowCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "show <fqn>",
		Short: "Show source code for a class",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return withRenderedServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				return c.GetSource(ctx, args[0])
			}, render.SourceShow)
		},
	}
}

func newSourceMethodCmd() *cobra.Command {
	var paramTypes string
	var contextLines int
	cmd := &cobra.Command{
		Use:   "method <fqn> <method>",
		Short: "Show source code for a specific method",
		Args:  cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			var pts []string
			if paramTypes != "" {
				pts = strings.Split(paramTypes, ",")
			}
			return withRenderedServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				return c.GetMethodSource(ctx, args[0], args[1], pts, contextLines)
			}, render.SourceMethod)
		},
	}
	cmd.Flags().StringVar(&paramTypes, "param-types", "", "Comma-separated parameter types for disambiguation")
	cmd.Flags().IntVar(&contextLines, "context", 0, "Context lines before/after method")
	return cmd
}
