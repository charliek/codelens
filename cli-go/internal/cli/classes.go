package cli

import (
	"context"

	"github.com/charliek/codelens/cli-go/internal/client"
	"github.com/spf13/cobra"
)

func newClassesCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "classes",
		Short: "Analyze and explore classes in the codebase",
	}
	cmd.AddCommand(
		newClassesListCmd(),
		newClassesShowCmd(),
		newClassesStatsCmd(),
		newClassesImplementationsCmd(),
		newClassesHierarchyCmd(),
		newClassesDependenciesCmd(),
	)
	return cmd
}

func newClassesListCmd() *cobra.Command {
	var f client.ListClassesFilter
	cmd := &cobra.Command{
		Use:   "list",
		Short: "List classes with optional filtering",
		RunE: func(cmd *cobra.Command, _ []string) error {
			return withRunningServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				return c.ListClasses(ctx, f)
			})
		},
	}
	cmd.Flags().StringVar(&f.Package, "package", "", "Filter by package pattern (supports *)")
	cmd.Flags().StringVar(&f.Name, "name", "", "Filter by class name pattern (supports *)")
	cmd.Flags().StringVar(&f.Annotation, "annotation", "", "Filter to classes with this annotation")
	cmd.Flags().StringVar(&f.Extends, "extends", "", "Filter to classes extending this class")
	cmd.Flags().StringVar(&f.Implements, "implements", "", "Filter to classes implementing this interface")
	cmd.Flags().BoolVarP(&f.InterfacesOnly, "interfaces", "i", false, "Only show interfaces")
	cmd.Flags().BoolVarP(&f.IncludeLibraries, "include-libraries", "L", false, "Include library classes")
	cmd.Flags().IntVar(&f.Page, "page", 0, "Page number (0-based)")
	cmd.Flags().IntVar(&f.Size, "size", 50, "Page size")
	return cmd
}

func newClassesShowCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "show <fqn>",
		Short: "Show detailed information about a specific class",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return withRunningServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				return c.GetClass(ctx, args[0])
			})
		},
	}
}

func newClassesStatsCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "stats",
		Short: "Show scan statistics for the codebase",
		RunE: func(cmd *cobra.Command, _ []string) error {
			return withRunningServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				return c.Stats(ctx)
			})
		},
	}
}

func newClassesImplementationsCmd() *cobra.Command {
	var includeLibraries bool
	cmd := &cobra.Command{
		Use:   "implementations <fqn>",
		Short: "Find implementations of an interface or subclasses of a class",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return withRunningServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				return c.GetImplementations(ctx, args[0], includeLibraries)
			})
		},
	}
	cmd.Flags().BoolVarP(&includeLibraries, "include-libraries", "L", false, "Include library classes")
	return cmd
}

func newClassesHierarchyCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "hierarchy <fqn>",
		Short: "Show the class hierarchy for a class",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return withRunningServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				return c.GetHierarchy(ctx, args[0])
			})
		},
	}
}

func newClassesDependenciesCmd() *cobra.Command {
	var includeLibraries bool
	cmd := &cobra.Command{
		Use:   "dependencies <fqn>",
		Short: "Show dependencies for a class (incoming and outgoing)",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return withRunningServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				return c.GetDependencies(ctx, args[0], includeLibraries)
			})
		},
	}
	cmd.Flags().BoolVarP(&includeLibraries, "include-libraries", "L", false, "Include library classes")
	return cmd
}
