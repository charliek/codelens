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
	cmd.Flags().StringVarP(&f.Name, "name", "n", "", "Filter by method name (supports *)")
	cmd.Flags().StringVarP(&f.ReturnType, "return-type", "r", "", "Filter by return type FQN")
	cmd.Flags().StringVarP(&f.Annotation, "annotation", "a", "", "Filter by annotation")
	// Python flag names: `--class` and `--package`. NOT `--in-class` / `--in-package`.
	cmd.Flags().StringVarP(&f.InClass, "class", "c", "", "Filter by containing class FQN")
	cmd.Flags().StringVar(&f.InPackage, "package", "", "Filter by containing package pattern")
	cmd.Flags().BoolVarP(&f.IncludeLibraries, "include-libraries", "L", false, "Include library classes")
	cmd.Flags().IntVar(&f.Page, "page", 0, "Page number (0-based)")
	cmd.Flags().IntVar(&f.Size, "size", 50, "Page size")
	return cmd
}
