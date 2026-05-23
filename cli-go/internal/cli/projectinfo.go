package cli

import (
	"context"

	"github.com/charliek/codelens/cli-go/internal/client"
	"github.com/spf13/cobra"
)

// newProjectCmd is `codelens project` — no subcommand. Mirrors the Python
// CLI exactly (cli/src/codelens_cli/main.py:46:
// `app.command(name="project")(project.project_info)`).
func newProjectCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "project",
		Short: "Show project info",
		RunE: func(cmd *cobra.Command, _ []string) error {
			return withRunningServer(cmd, func(ctx context.Context, c *client.Client) (any, error) {
				return c.Project(ctx)
			})
		},
	}
}
