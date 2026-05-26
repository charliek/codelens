package render

import (
	"fmt"
	"io"
	"strings"
)

type xrefRef struct {
	Kind           string `json:"kind"`
	FromFqn        string `json:"fromFqn"`
	FromSimpleName string `json:"fromSimpleName"`
	FromSource     string `json:"fromSource"`
	Member         string `json:"member"`
	Detail         string `json:"detail"`
	LineNumber     int    `json:"lineNumber"`
}

type xrefResponse struct {
	TypeFqn         string         `json:"typeFqn"`
	References      []xrefRef      `json:"references"`
	CountsByKind    map[string]int `json:"countsByKind"`
	CountsByPackage map[string]int `json:"countsByPackage"`
	Page            int            `json:"page"`
	PageSize        int            `json:"pageSize"`
	TotalCount      int            `json:"totalCount"`
	TotalPages      int            `json:"totalPages"`
}

// Xref renders `xref <typeFqn>`: a per-kind count summary plus a flat table of
// every reference (who references the type, via what member, where).
func Xref(w io.Writer, v any) error {
	resp, err := decode[xrefResponse](v)
	if err != nil {
		return err
	}
	fmt.Fprintf(w, "References to %s\n", resp.TypeFqn)
	if resp.TotalCount == 0 {
		fmt.Fprintln(w, "\nNo references found.")
		return nil
	}
	if len(resp.CountsByKind) > 0 {
		var parts []string
		for _, k := range sortedKeys(resp.CountsByKind) {
			parts = append(parts, fmt.Sprintf("%s=%d", k, resp.CountsByKind[k]))
		}
		fmt.Fprintf(w, "Total: %d | %s\n", resp.TotalCount, strings.Join(parts, ", "))
	}
	fmt.Fprintln(w)

	rows := make([][]string, 0, len(resp.References))
	for _, r := range resp.References {
		rows = append(rows, []string{
			r.Kind,
			dash(r.FromSimpleName),
			dash(r.Member),
			lineStr(r.LineNumber),
			dash(r.Detail),
		})
	}
	if err := Table(w, []string{"Kind", "From", "Member", "Line", "Detail"}, rows); err != nil {
		return err
	}
	if resp.TotalPages > 1 {
		fmt.Fprintf(w, "\nPage %d of %d. Use --page to navigate.\n", resp.Page+1, resp.TotalPages)
	}
	return nil
}
