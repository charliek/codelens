// Package cli wires the Cobra command tree.
package cli

import (
	"errors"
	"fmt"
	"os"

	clierrors "github.com/charliek/codelens/cli/internal/errors"
	"github.com/spf13/cobra"
)

// Globals populated by persistent flags. PersistentPreRun resolves them.
var (
	flagProject string
	flagJSON    bool
)

func newRootCmd() *cobra.Command {
	root := &cobra.Command{
		Use:           "codelens",
		Short:         "Analyze Ratpack-based JVM codebases for migration planning.",
		SilenceUsage:  true,
		SilenceErrors: true,
	}

	root.PersistentFlags().StringVarP(&flagProject, "project", "p", "", "Path to the target Gradle project (defaults to cwd)")
	root.PersistentFlags().BoolVar(&flagJSON, "json", false, "Emit JSON output (auto-enabled when stdout is not a TTY)")

	root.AddCommand(
		newVersionCmd(),
		newStartCmd(),
		newStopCmd(),
		newStatusCmd(),
		newRestartCmd(),
		newRefreshCmd(),
		newListCmd(),
		// analysis groups
		newClassesCmd(),
		newMethodsCmd(),
		newCallsCmd(),
		newAnnotationsCmd(),
		newSourceCmd(),
		newHandlersCmd(),
		newPromisesCmd(),
		newMigrationCmd(),
		newModulesCmd(),
		newIntegrationsCmd(),
		newAntipatternsCmd(),
		newRoutesCmd(),
		newDepsCmd(),
		newLintCmd(),
		newProjectCmd(),
	)

	return root
}

// Execute runs the root command and exits with the appropriate ExitCode.
func Execute() {
	root := newRootCmd()
	err := root.Execute()
	if err == nil {
		os.Exit(int(clierrors.Success))
	}

	var cliErr *clierrors.CLIError
	if errors.As(err, &cliErr) {
		if cliErr.Message != "" {
			fmt.Fprintln(os.Stderr, "Error:", cliErr.Message)
		}
		os.Exit(int(cliErr.Code))
	}

	// Cobra usage errors (unknown command, missing required flag).
	if isUsageError(err) {
		fmt.Fprintln(os.Stderr, "Error:", err.Error())
		os.Exit(int(clierrors.InvalidUsage))
	}

	fmt.Fprintln(os.Stderr, "Error:", err.Error())
	os.Exit(int(clierrors.GeneralError))
}

// isUsageError returns true for Cobra errors that should map to exit code 2.
func isUsageError(err error) bool {
	if err == nil {
		return false
	}
	msg := err.Error()
	for _, prefix := range []string{
		"unknown command",
		"unknown flag",
		"unknown shorthand flag",
		"flag needs an argument",
		"invalid argument",
		"required flag(s)",
		"accepts ",
	} {
		if len(msg) >= len(prefix) && msg[:len(prefix)] == prefix {
			return true
		}
	}
	return false
}
