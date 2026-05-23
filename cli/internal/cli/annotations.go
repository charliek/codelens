package cli

import (
	"context"

	"github.com/charliek/codelens/cli/internal/client"
	"github.com/spf13/cobra"
)

func newAnnotationsCmd() *cobra.Command {
	cmd := &cobra.Command{Use: "annotations", Short: "Annotation usage analysis"}
	cmd.AddCommand(newAnnotationsUsagesCmd())
	return cmd
}

func newAnnotationsUsagesCmd() *cobra.Command {
	var includeLibraries bool
	cmd := &cobra.Command{
		Use:   "usages <annotation-fqn>",
		Short: "Find classes using a specific annotation",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return withRunningServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				return c.GetAnnotationUsages(ctx, args[0], includeLibraries)
			})
		},
	}
	cmd.Flags().BoolVarP(&includeLibraries, "include-libraries", "L", false, "Include library classes")
	return cmd
}
