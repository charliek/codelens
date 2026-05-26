package render

import (
	"fmt"
	"io"
)

type annotationUsagesResponse struct {
	AnnotationFqn string         `json:"annotationFqn"`
	TotalCount    int            `json:"totalCount"`
	Usages        []classSummary `json:"usages"`
}

// AnnotationUsages renders `annotations usages <fqn>`.
func AnnotationUsages(w io.Writer, v any) error {
	resp, err := decode[annotationUsagesResponse](v)
	if err != nil {
		return err
	}
	fmt.Fprintf(w, "Usages of @%s (%d)\n\n", resp.AnnotationFqn, resp.TotalCount)
	if resp.TotalCount == 0 {
		fmt.Fprintln(w, "No usages found.")
		return nil
	}
	rows := make([][]string, 0, len(resp.Usages))
	for _, c := range resp.Usages {
		rows = append(rows, []string{c.SimpleName, c.PackageName, c.Source})
	}
	return Table(w, []string{"Class", "Package", "Source"}, rows)
}
