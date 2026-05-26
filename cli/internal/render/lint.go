package render

import (
	"fmt"
	"io"

	"github.com/charliek/codelens/cli/internal/client"
)

// LintCheck renders `lint check` for either a single file or a whole project.
// The exit code (1 on violations) is driven by the command, not this renderer.
func LintCheck(w io.Writer, v any) error {
	switch r := v.(type) {
	case *client.LintFileResponse:
		return lintFile(w, r)
	case *client.LintProjectResponse:
		return lintProject(w, r)
	default:
		return ErrFallback
	}
}

func lintFile(w io.Writer, r *client.LintFileResponse) error {
	fmt.Fprintln(w, r.FilePath)
	if r.ErrorCount == 0 {
		fmt.Fprintln(w, "No violations.")
		return nil
	}
	if err := violationTable(w, r.Errors); err != nil {
		return err
	}
	fmt.Fprintf(w, "\n%d violation(s).\n", r.ErrorCount)
	return nil
}

func lintProject(w io.Writer, r *client.LintProjectResponse) error {
	fmt.Fprintf(w, "Lint: %s\n", r.ProjectPath)
	fmt.Fprintf(w, "Scanned %d file(s); %d with violations; %d total.\n",
		r.FilesScanned, r.FilesWithErrors, r.TotalErrorCount)
	for _, f := range r.FileResults {
		if f.ErrorCount == 0 {
			continue
		}
		fmt.Fprintf(w, "\n%s\n", f.FilePath)
		if err := violationTable(w, f.Errors); err != nil {
			return err
		}
	}
	return nil
}

func violationTable(w io.Writer, errs []client.LintError) error {
	rows := make([][]string, 0, len(errs))
	for _, e := range errs {
		rows = append(rows, []string{fmt.Sprintf("%d:%d", e.Line, e.Col), e.RuleID, e.Detail})
	}
	return Table(w, []string{"Pos", "Rule", "Detail"}, rows)
}

// LintFormat renders `lint format` for either a single file or a whole project.
func LintFormat(w io.Writer, v any) error {
	switch r := v.(type) {
	case *client.FormatFileResponse:
		status := "no changes"
		if r.HasChanges {
			status = "formatted"
		}
		fmt.Fprintf(w, "%s: %s", r.FilePath, status)
		if n := len(r.RemainingErrors); n > 0 {
			fmt.Fprintf(w, " (%d remaining violation(s))", n)
		}
		fmt.Fprintln(w)
		return nil
	case *client.FormatProjectResponse:
		fmt.Fprintf(w, "Format: %s\n", r.ProjectPath)
		fmt.Fprintf(w, "Scanned %d file(s); %d changed.\n", r.FilesScanned, r.FilesWithChanges)
		for _, f := range r.FilesFormatted {
			fmt.Fprintf(w, "  %s\n", f)
		}
		return nil
	default:
		return ErrFallback
	}
}
