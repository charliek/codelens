package render

import (
	"fmt"
	"io"
)

type methodMatch struct {
	ClassFqn        string     `json:"classFqn"`
	ClassSimpleName string     `json:"classSimpleName"`
	ClassSource     string     `json:"classSource"`
	Method          methodInfo `json:"method"`
}

type methodSearchResponse struct {
	Methods    []methodMatch `json:"methods"`
	Page       int           `json:"page"`
	PageSize   int           `json:"pageSize"`
	TotalCount int           `json:"totalCount"`
	TotalPages int           `json:"totalPages"`
}

// MethodsSearch renders `methods search`.
func MethodsSearch(w io.Writer, v any) error {
	resp, err := decode[methodSearchResponse](v)
	if err != nil {
		return err
	}
	if resp.TotalCount == 0 {
		fmt.Fprintln(w, "No methods found matching the filter.")
		return nil
	}
	start := resp.Page*resp.PageSize + 1
	end := start + len(resp.Methods) - 1
	fmt.Fprintf(w, "Methods (%d-%d of %d)\n\n", start, end, resp.TotalCount)

	rows := make([][]string, 0, len(resp.Methods))
	for _, m := range resp.Methods {
		rows = append(rows, []string{
			m.ClassSimpleName,
			m.Method.Name,
			shortType(m.Method.ReturnType),
			paramList(m.Method.Parameters),
		})
	}
	if err := Table(w, []string{"Class", "Method", "Return", "Parameters"}, rows); err != nil {
		return err
	}
	if resp.TotalPages > 1 {
		fmt.Fprintf(w, "\nPage %d of %d. Use --page to navigate.\n", resp.Page+1, resp.TotalPages)
	}
	return nil
}
