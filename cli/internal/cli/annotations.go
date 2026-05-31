package cli

import (
	"context"
	"strings"

	"github.com/charliek/codelens/cli/internal/client"
	clierrors "github.com/charliek/codelens/cli/internal/errors"
	"github.com/charliek/codelens/cli/internal/render"
	"github.com/spf13/cobra"
)

func newAnnotationsCmd() *cobra.Command {
	cmd := &cobra.Command{Use: "annotations", Short: "Annotation usage analysis", GroupID: "analyze"}
	cmd.AddCommand(newAnnotationsUsagesCmd())
	return cmd
}

// validAnnotationScopes are the accepted --scope values (lowercased); the server
// upper-cases before parsing the AnnotationScope enum.
var validAnnotationScopes = []string{"class", "method", "field", "param", "all"}

func newAnnotationsUsagesCmd() *cobra.Command {
	var f client.AnnotationUsagesFilter
	cmd := &cobra.Command{
		Use:   "usages <annotation-fqn>",
		Short: "Find where an annotation is used, with its attribute values",
		Long: "Find every place an annotation is applied — across class, method, constructor, " +
			"field, and parameter targets — with the matched annotation's typed attribute values " +
			"inline.\n\n" +
			"--scope selects the declaration sites: class, method, field, param, or all (default). " +
			"--scope method also surfaces constructors (each row carries a `target`, so JSON " +
			"consumers can select(.target==\"METHOD\")). Matching is meta-expanded: querying a " +
			"meta-annotation (e.g. @RequestMapping) also matches @GetMapping methods, returning the " +
			"synthesized instance's attributes (the path + the HTTP verb).",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			f.Scope = strings.ToLower(strings.TrimSpace(f.Scope))
			if err := validateAnnotationScope(f.Scope); err != nil {
				return err
			}
			// Fail fast on bad pagination (the server also 400s), so an explicit
			// --size 0 / negative isn't silently coerced to the default.
			if f.Page < 0 || f.Size < 1 {
				return clierrors.New(clierrors.InvalidUsage, "--page must be >= 0 and --size must be >= 1")
			}
			return withRenderedServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				return c.GetAnnotationUsages(ctx, args[0], f)
			}, render.AnnotationUsages)
		},
	}
	cmd.Flags().StringVar(&f.Scope, "scope", "all",
		"Declaration sites to scan: class, method, field, param, or all")
	cmd.Flags().BoolVarP(&f.IncludeLibraries, "include-libraries", "L", false, "Include library classes")
	cmd.Flags().IntVar(&f.Page, "page", 0, "Page number (0-based)")
	cmd.Flags().IntVar(&f.Size, "size", 50, "Page size")
	return cmd
}

func validateAnnotationScope(scope string) error {
	for _, s := range validAnnotationScopes {
		if scope == s {
			return nil
		}
	}
	return clierrors.New(clierrors.InvalidUsage,
		"invalid --scope %q: must be one of %s", scope, strings.Join(validAnnotationScopes, ", "))
}
