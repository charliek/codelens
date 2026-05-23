package cli

import (
	"fmt"

	"github.com/charliek/codelens/cli-go/internal/version"
	"github.com/spf13/cobra"
)

func newVersionCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "version",
		Short: "Show CodeLens CLI version",
		RunE: func(cmd *cobra.Command, _ []string) error {
			_, err := fmt.Fprintf(cmd.OutOrStdout(), "codelens-cli %s\n", version.Value)
			return err
		},
	}
}
