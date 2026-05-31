package render

import (
	"fmt"
	"io"
	"strconv"
	"strings"
)

// annValue mirrors the server's typed AnnotationValue (a discriminated union over
// kind). It is mutually recursive with annInfo via ANNOTATION / ARRAY.
type annValue struct {
	Kind       string     `json:"kind"`
	Value      string     `json:"value"`
	EnumType   string     `json:"enumType"`
	Annotation *annInfo   `json:"annotation"`
	Items      []annValue `json:"items"`
}

type annInfo struct {
	Type       string              `json:"type"`
	Parameters map[string]annValue `json:"parameters"`
}

type annotationUsage struct {
	Target          string  `json:"target"`
	ClassFqn        string  `json:"classFqn"`
	ClassSimpleName string  `json:"classSimpleName"`
	PackageName     string  `json:"packageName"`
	Source          string  `json:"source"`
	Method          string  `json:"method"`
	Descriptor      string  `json:"descriptor"`
	Field           string  `json:"field"`
	ParameterName   string  `json:"parameterName"`
	ParameterIndex  *int    `json:"parameterIndex"`
	ParameterType   string  `json:"parameterType"`
	Annotation      annInfo `json:"annotation"`
}

type annotationUsagesResponse struct {
	AnnotationFqn  string            `json:"annotationFqn"`
	Usages         []annotationUsage `json:"usages"`
	TotalCount     int               `json:"totalCount"`
	Page           int               `json:"page"`
	PageSize       int               `json:"pageSize"`
	TotalPages     int               `json:"totalPages"`
	CountsByTarget map[string]int    `json:"countsByTarget"`
	AppliedFilter  struct {
		IncludeLibraries bool   `json:"includeLibraries"`
		Scope            string `json:"scope"`
	} `json:"appliedFilter"`
}

// annotationTargetOrder is the canonical display/sort order of usage targets
// (matches the server's AnnotationUsageTarget declaration order).
var annotationTargetOrder = []string{"CLASS", "METHOD", "CONSTRUCTOR", "FIELD", "PARAMETER"}

// AnnotationUsages renders `annotations usages <fqn>`: a per-target count summary
// plus a table of every usage (where the annotation is applied, on what member,
// and its attribute values).
func AnnotationUsages(w io.Writer, v any) error {
	resp, err := decode[annotationUsagesResponse](v)
	if err != nil {
		return err
	}
	fmt.Fprintf(w, "Usages of @%s (%d total, scope=%s)\n", resp.AnnotationFqn, resp.TotalCount, resp.AppliedFilter.Scope)
	if resp.TotalCount == 0 {
		fmt.Fprintln(w, "\nNo usages found.")
		return nil
	}
	if counts := orderedTargetCounts(resp.CountsByTarget); len(counts) > 0 {
		fmt.Fprintln(w, strings.Join(counts, " "))
	}
	fmt.Fprintln(w)

	rows := make([][]string, 0, len(resp.Usages))
	for _, u := range resp.Usages {
		rows = append(rows, []string{
			u.Target,
			dash(u.ClassSimpleName),
			dash(annotationMember(u)),
			dash(formatAnnotationParams(u.Annotation)),
		})
	}
	if err := Table(w, []string{"Target", "Class", "Member", "Attributes"}, rows); err != nil {
		return err
	}
	if resp.TotalPages > 1 {
		fmt.Fprintf(w, "\nPage %d of %d. Use --page to navigate.\n", resp.Page+1, resp.TotalPages)
	}
	return nil
}

// orderedTargetCounts formats countsByTarget as "CLASS=5 METHOD=2", known targets
// first in canonical order, any unknown (future) targets appended sorted.
func orderedTargetCounts(counts map[string]int) []string {
	parts := make([]string, 0, len(counts))
	seen := make(map[string]bool, len(annotationTargetOrder))
	for _, t := range annotationTargetOrder {
		if n, ok := counts[t]; ok {
			parts = append(parts, fmt.Sprintf("%s=%d", t, n))
			seen[t] = true
		}
	}
	for _, k := range sortedKeys(counts) {
		if !seen[k] {
			parts = append(parts, fmt.Sprintf("%s=%d", k, counts[k]))
		}
	}
	return parts
}

// annotationMember renders the per-target member identity for the table.
func annotationMember(u annotationUsage) string {
	switch u.Target {
	case "FIELD":
		return u.Field
	case "PARAMETER":
		loc := u.Method
		if u.ParameterIndex != nil {
			loc += "#" + strconv.Itoa(*u.ParameterIndex)
		}
		return fmt.Sprintf("%s %s: %s", loc, dash(u.ParameterName), dash(shortType(u.ParameterType)))
	case "CONSTRUCTOR":
		// descriptor is a derived "(type,…)" parameter-type signature.
		return u.Method + shortType(u.Descriptor)
	case "METHOD":
		return u.Method
	default: // CLASS — the class is already in the Class column.
		return ""
	}
}

// formatAnnotationParams renders an annotation's attributes as a compact,
// deterministic one-liner (keys sorted), e.g. `method=[GET], value=["/{id}"]`.
// Empty arrays / empty strings are omitted to keep the table readable — a
// meta-expanded @RequestMapping otherwise carries every default attribute
// (consumes=[], headers=[], …); the full set is still in the --json output.
func formatAnnotationParams(a annInfo) string {
	parts := make([]string, 0, len(a.Parameters))
	for _, k := range sortedKeys(a.Parameters) {
		v := a.Parameters[k]
		if isEmptyAnnotationValue(v) {
			continue
		}
		parts = append(parts, k+"="+formatAnnotationValue(v))
	}
	return strings.Join(parts, ", ")
}

// isEmptyAnnotationValue reports whether a value adds nothing to the table (an
// empty array or an empty string) and should be omitted from the Attributes cell.
func isEmptyAnnotationValue(v annValue) bool {
	switch v.Kind {
	case "ARRAY":
		return len(v.Items) == 0
	case "STRING":
		return v.Value == ""
	default:
		return false
	}
}

// formatAnnotationValue renders one typed value: strings are quoted, class
// literals shortened to their simple name, arrays bracketed, nested annotations
// rendered as @Type(...), everything else (numbers/booleans/enums) raw.
func formatAnnotationValue(v annValue) string {
	switch v.Kind {
	case "ARRAY":
		parts := make([]string, 0, len(v.Items))
		for _, it := range v.Items {
			parts = append(parts, formatAnnotationValue(it))
		}
		return "[" + strings.Join(parts, ", ") + "]"
	case "ANNOTATION":
		if v.Annotation == nil {
			return "@?"
		}
		return "@" + simpleName(v.Annotation.Type) + "(" + formatAnnotationParams(*v.Annotation) + ")"
	case "CLASS":
		return simpleName(v.Value)
	case "STRING":
		return strconv.Quote(v.Value)
	default: // BOOLEAN, INT, LONG, FLOAT, DOUBLE, BYTE, SHORT, CHAR, ENUM
		return v.Value
	}
}
