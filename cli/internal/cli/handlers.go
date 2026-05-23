package cli

import (
	"context"
	"encoding/json"

	"github.com/charliek/codelens/cli/internal/client"
	"github.com/spf13/cobra"
)

func newHandlersCmd() *cobra.Command {
	cmd := &cobra.Command{Use: "handlers", Short: "Ratpack handler analysis"}
	cmd.AddCommand(newHandlersListCmd(), newHandlersShowCmd())
	return cmd
}

func newHandlersListCmd() *cobra.Command {
	var handlerType, tier string
	var includeLibraries, missingInject bool
	cmd := &cobra.Command{
		Use:   "list",
		Short: "List Ratpack handlers",
		RunE: func(cmd *cobra.Command, _ []string) error {
			result, err := runWithServer(func(ctx context.Context, c *client.Client) (any, error) {
				return c.ListHandlers(ctx, handlerType, tier, includeLibraries)
			})
			if err != nil {
				return err
			}
			if missingInject {
				// Client-side filter — there's no server-side flag for
				// this. Mirrors Python handlers.py:174-178.
				if raw, ok := result.(json.RawMessage); ok {
					filtered, ferr := filterMissingInject(raw)
					if ferr == nil {
						result = filtered
					}
				}
			}
			return emitAnalysisResult(cmd, result)
		},
	}
	cmd.Flags().StringVarP(&handlerType, "type", "t", "", "Filter by handler type (HANDLER, CHAIN_ACTION, INLINE_HANDLER, GROOVY_HANDLER)")
	cmd.Flags().StringVar(&tier, "tier", "", "Filter by complexity tier (LOW, MEDIUM, HIGH, CRITICAL)")
	cmd.Flags().BoolVarP(&missingInject, "missing-inject", "I", false, "Only show handlers without @Inject annotation")
	cmd.Flags().BoolVarP(&includeLibraries, "include-libraries", "L", false, "Include library classes")
	return cmd
}

// filterMissingInject drops every entry in result["handlers"] that has
// hasInjectAnnotation == true. Returns a json.RawMessage rebuilt from the
// filtered handlers; other fields (e.g. totalCount) are passed through.
// Server key order is preserved on the surviving entries (each handler
// stays as json.RawMessage).
func filterMissingInject(raw json.RawMessage) (json.RawMessage, error) {
	// Decode top-level into a generic map, keeping the handlers array
	// elements as RawMessages so per-handler key order survives.
	var top map[string]json.RawMessage
	if err := json.Unmarshal(raw, &top); err != nil {
		return raw, err
	}
	handlersRaw, ok := top["handlers"]
	if !ok {
		return raw, nil
	}
	var entries []json.RawMessage
	if err := json.Unmarshal(handlersRaw, &entries); err != nil {
		return raw, err
	}
	kept := make([]json.RawMessage, 0, len(entries))
	for _, e := range entries {
		// Peek just `hasInjectAnnotation`. A missing field == false (no inject).
		var probe struct {
			HasInject bool `json:"hasInjectAnnotation"`
		}
		_ = json.Unmarshal(e, &probe)
		if !probe.HasInject {
			kept = append(kept, e)
		}
	}
	filtered, err := json.Marshal(kept)
	if err != nil {
		return raw, err
	}
	top["handlers"] = filtered
	return json.Marshal(top)
}

func newHandlersShowCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "show <fqn>",
		Short: "Show handler details with migration notes",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return withRunningServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				return c.GetHandler(ctx, args[0])
			})
		},
	}
}
