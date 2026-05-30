// Package cli wires the Cobra command tree.
package cli

import (
	"errors"
	"fmt"
	"os"

	clierrors "github.com/charliek/codelens/cli/internal/errors"
	"github.com/spf13/cobra"
)

// Globals populated by persistent flags. resolveMode() reads flagJSON/flagTable
// to pick the output mode; resolveProjectPath reads flagProject.
var (
	flagProject string
	flagJSON    bool
	flagTable   bool
)

func newRootCmd() *cobra.Command {
	root := &cobra.Command{
		Use:           "codelens",
		Short:         "Analyze JVM codebases: classes, methods, calls, cross-references, and dependencies.",
		SilenceUsage:  true,
		SilenceErrors: true,
	}

	root.PersistentFlags().StringVarP(&flagProject, "project", "p", "", "Path to the target Gradle project (defaults to cwd)")
	root.PersistentFlags().BoolVar(&flagJSON, "json", false, "Force JSON output (default when stdout is not a TTY)")
	root.PersistentFlags().BoolVar(&flagTable, "table", false, "Force human-readable table output (default on a TTY)")
	// --json and --table are opposites; reject both at once. Marking on the
	// root command covers every subcommand because cobra merges persistent
	// flags and validates flag groups per command during execute().
	root.MarkFlagsMutuallyExclusive("json", "table")

	root.AddGroup(
		&cobra.Group{ID: "lifecycle", Title: "Server lifecycle:"},
		&cobra.Group{ID: "analyze", Title: "Code analysis:"},
		&cobra.Group{ID: "tools", Title: "Kotlin tooling:"},
	)

	root.AddCommand(
		newVersionCmd(),
		newStartCmd(),
		newStopCmd(),
		newStatusCmd(),
		newRestartCmd(),
		newRefreshCmd(),
		newListCmd(),
		newClassesCmd(),
		newMethodsCmd(),
		newCallsCmd(),
		newXrefCmd(),
		newAnnotationsCmd(),
		newSourceCmd(),
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
		"if any flags in the group", // cobra's MarkFlagsMutuallyExclusive violation
	} {
		if len(msg) >= len(prefix) && msg[:len(prefix)] == prefix {
			return true
		}
	}
	return false
}
