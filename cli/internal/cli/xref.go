package cli

import (
	"context"

	"github.com/charliek/codelens/cli/internal/client"
	"github.com/spf13/cobra"
)

// newXrefCmd is `codelens xref <typeFqn>` — the inverse cross-reference
// primitive. It returns everything across the project that references a type
// (supertypes, fields, params/returns, annotations, instantiations, call
// receivers), grouped by kind, with server-side narrowing.
func newXrefCmd() *cobra.Command {
	var f client.XrefFilter
	cmd := &cobra.Command{
		Use:   "xref <typeFqn>",
		Short: "Find everything that references a type (inverse cross-reference)",
		Long: "Find every reference to a type across the project: who extends/implements it, " +
			"holds it as a field, takes or returns it, is annotated with it, instantiates it, or " +
			"calls methods on it. Results are grouped by kind with package/kind aggregates.",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return withRunningServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				return c.GetXref(ctx, args[0], f)
			})
		},
	}
	cmd.Flags().BoolVarP(&f.IncludeLibraries, "include-libraries", "L", false, "Include references from library classes")
	cmd.Flags().StringVar(&f.Kind, "kind", "", "Restrict to one kind: EXTENDS, IMPLEMENTS, FIELD, PARAM, RETURN, ANNOTATION, INSTANTIATION, CALL_RECEIVER")
	cmd.Flags().StringVar(&f.ScopeImplementing, "scope-implementing", "", "Only count references from classes that implement (or extend) this type")
	cmd.Flags().IntVar(&f.Page, "page", 0, "Page number (0-based)")
	cmd.Flags().IntVar(&f.Size, "size", 50, "Page size")
	return cmd
}
