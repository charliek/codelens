package cli

import (
	"context"

	"github.com/charliek/codelens/cli-go/internal/client"
	"github.com/spf13/cobra"
)

func newMethodsCmd() *cobra.Command {
	cmd := &cobra.Command{Use: "methods", Short: "Search methods across the codebase"}
	cmd.AddCommand(newMethodsSearchCmd())
	return cmd
}

func newMethodsSearchCmd() *cobra.Command {
	var f client.SearchMethodsFilter
	cmd := &cobra.Command{
		Use:   "search",
		Short: "Search methods with filters",
		RunE: func(cmd *cobra.Command, _ []string) error {
			return withRunningServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				return c.SearchMethods(ctx, f)
			})
		},
	}
	cmd.Flags().StringVar(&f.Name, "name", "", "Filter by method name (supports *)")
	cmd.Flags().StringVar(&f.ReturnType, "return-type", "", "Filter by return type")
	cmd.Flags().StringVar(&f.Annotation, "annotation", "", "Filter by annotation")
	cmd.Flags().StringVar(&f.InClass, "in-class", "", "Filter to methods in this class FQN")
	cmd.Flags().StringVar(&f.InPackage, "in-package", "", "Filter to methods in this package")
	cmd.Flags().BoolVarP(&f.IncludeLibraries, "include-libraries", "L", false, "Include library classes")
	cmd.Flags().IntVar(&f.Page, "page", 0, "Page number (0-based)")
	cmd.Flags().IntVar(&f.Size, "size", 50, "Page size")
	return cmd
}
