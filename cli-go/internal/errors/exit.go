// Package errors defines the CLI's exit-code contract.
//
// The codes here mirror cli/src/codelens_cli/errors.py and are part of the
// CLI's public contract — scripts depend on specific codes.
package errors

import "fmt"

type ExitCode int

const (
	Success         ExitCode = 0
	GeneralError    ExitCode = 1
	InvalidUsage    ExitCode = 2
	ProjectNotFound ExitCode = 3
	ServerError     ExitCode = 4
	Timeout         ExitCode = 5
	ConnectionError ExitCode = 6
	NotRunning      ExitCode = 7
)

type CLIError struct {
	Code    ExitCode
	Message string
}

func (e *CLIError) Error() string {
	return e.Message
}

func New(code ExitCode, format string, args ...any) *CLIError {
	return &CLIError{Code: code, Message: fmt.Sprintf(format, args...)}
}
