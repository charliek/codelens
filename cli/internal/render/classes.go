package render

import (
	"fmt"
	"io"
	"strconv"
	"strings"
)

// classSummary is the shared shape used by `classes list`, implementations,
// and annotation usages.
type classSummary struct {
	Fqn          string `json:"fqn"`
	SimpleName   string `json:"simpleName"`
	PackageName  string `json:"packageName"`
	Source       string `json:"source"`
	MethodCount  int    `json:"methodCount"`
	FieldCount   int    `json:"fieldCount"`
	IsInterface  bool   `json:"isInterface"`
	IsAnnotation bool   `json:"isAnnotation"`
	IsEnum       bool   `json:"isEnum"`
	IsAbstract   bool   `json:"isAbstract"`
}

func (c classSummary) typeStr() string {
	switch {
	case c.IsInterface:
		return "interface"
	case c.IsAnnotation:
		return "annotation"
	case c.IsEnum:
		return "enum"
	case c.IsAbstract:
		return "abstract"
	default:
		return "class"
	}
}

type appliedFilter struct {
	PackagePattern      string `json:"packagePattern"`
	NamePattern         string `json:"namePattern"`
	HasAnnotation       string `json:"hasAnnotation"`
	ExtendsClass        string `json:"extendsClass"`
	ImplementsInterface string `json:"implementsInterface"`
	Source              string `json:"source"`
}

func (f appliedFilter) summary() string {
	var parts []string
	add := func(label, val string) {
		if val != "" {
			parts = append(parts, label+"="+val)
		}
	}
	add("package", f.PackagePattern)
	add("name", f.NamePattern)
	add("annotation", f.HasAnnotation)
	add("extends", f.ExtendsClass)
	add("implements", f.ImplementsInterface)
	if len(parts) == 0 {
		return "none"
	}
	return strings.Join(parts, ", ")
}

type classListResponse struct {
	Classes       []classSummary `json:"classes"`
	AppliedFilter appliedFilter  `json:"appliedFilter"`
	Page          int            `json:"page"`
	PageSize      int            `json:"pageSize"`
	TotalCount    int            `json:"totalCount"`
	TotalPages    int            `json:"totalPages"`
}

// ClassesList renders `classes list`.
func ClassesList(w io.Writer, v any) error {
	resp, err := decode[classListResponse](v)
	if err != nil {
		return err
	}
	if resp.TotalCount == 0 {
		fmt.Fprintln(w, "No classes found matching the filter.")
		return nil
	}
	start := resp.Page*resp.PageSize + 1
	end := start + len(resp.Classes) - 1
	fmt.Fprintf(w, "Classes (%d-%d of %d) | Filter: %s\n\n", start, end, resp.TotalCount, resp.AppliedFilter.summary())

	rows := make([][]string, 0, len(resp.Classes))
	for _, c := range resp.Classes {
		rows = append(rows, []string{
			c.SimpleName, c.typeStr(), c.Source,
			strconv.Itoa(c.MethodCount), strconv.Itoa(c.FieldCount),
		})
	}
	if err := Table(w, []string{"Name", "Type", "Source", "Methods", "Fields"}, rows); err != nil {
		return err
	}
	if resp.TotalPages > 1 {
		fmt.Fprintf(w, "\nPage %d of %d. Use --page to navigate.\n", resp.Page+1, resp.TotalPages)
	}
	return nil
}

// ---- classes show ----

type qualifiedName struct {
	Fqn         string `json:"fqn"`
	PackageName string `json:"packageName"`
	SimpleName  string `json:"simpleName"`
}

type annotationInfo struct {
	Type string `json:"type"`
}

type paramInfo struct {
	Name string `json:"name"`
	Type string `json:"type"`
}

type methodInfo struct {
	Name        string      `json:"name"`
	ReturnType  string      `json:"returnType"`
	Parameters  []paramInfo `json:"parameters"`
	Visibility  string      `json:"visibility"`
	IsSynthetic bool        `json:"isSynthetic"`
}

type fieldInfo struct {
	Name       string `json:"name"`
	Type       string `json:"type"`
	Visibility string `json:"visibility"`
}

type classInfo struct {
	Name         qualifiedName    `json:"name"`
	Source       string           `json:"source"`
	Visibility   string           `json:"visibility"`
	Superclass   *string          `json:"superclass"`
	Interfaces   []string         `json:"interfaces"`
	Annotations  []annotationInfo `json:"annotations"`
	IsInterface  bool             `json:"isInterface"`
	IsAnnotation bool             `json:"isAnnotation"`
	IsEnum       bool             `json:"isEnum"`
	IsAbstract   bool             `json:"isAbstract"`
	Methods      []methodInfo     `json:"methods"`
	Fields       []fieldInfo      `json:"fields"`
}

func (c classInfo) typeStr() string {
	switch {
	case c.IsInterface:
		return "interface"
	case c.IsAnnotation:
		return "annotation"
	case c.IsEnum:
		return "enum"
	case c.IsAbstract:
		return "abstract class"
	default:
		return "class"
	}
}

type classDetailResponse struct {
	ClassInfo classInfo `json:"classInfo"`
}

// ClassesShow renders `classes show`.
func ClassesShow(w io.Writer, v any) error {
	resp, err := decode[classDetailResponse](v)
	if err != nil {
		return err
	}
	info := resp.ClassInfo
	fmt.Fprintf(w, "%s\n\n", info.Name.Fqn)

	kv := [][2]string{
		{"Package:", dash(info.Name.PackageName)},
		{"Type:", info.typeStr()},
		{"Visibility:", dash(info.Visibility)},
		{"Source:", dash(info.Source)},
	}
	if info.Superclass != nil && *info.Superclass != "" {
		kv = append(kv, [2]string{"Extends:", *info.Superclass})
	}
	if len(info.Interfaces) > 0 {
		kv = append(kv, [2]string{"Implements:", strings.Join(info.Interfaces, ", ")})
	}
	if len(info.Annotations) > 0 {
		anns := make([]string, len(info.Annotations))
		for i, a := range info.Annotations {
			anns[i] = "@" + simpleName(a.Type)
		}
		kv = append(kv, [2]string{"Annotations:", strings.Join(anns, ", ")})
	}
	if err := KVBlock(w, kv); err != nil {
		return err
	}

	var methods []methodInfo
	for _, m := range info.Methods {
		if !m.IsSynthetic {
			methods = append(methods, m)
		}
	}
	if len(methods) > 0 {
		fmt.Fprintf(w, "\nMethods (%d)\n", len(methods))
		rows := make([][]string, 0, len(methods))
		for _, m := range methods {
			rows = append(rows, []string{m.Name, m.Visibility, shortType(m.ReturnType), paramList(m.Parameters)})
		}
		if err := Table(w, []string{"Name", "Visibility", "Return", "Parameters"}, rows); err != nil {
			return err
		}
	}
	if len(info.Fields) > 0 {
		fmt.Fprintf(w, "\nFields (%d)\n", len(info.Fields))
		rows := make([][]string, 0, len(info.Fields))
		for _, f := range info.Fields {
			rows = append(rows, []string{f.Name, f.Visibility, shortType(f.Type)})
		}
		if err := Table(w, []string{"Name", "Visibility", "Type"}, rows); err != nil {
			return err
		}
	}
	return nil
}

func paramList(params []paramInfo) string {
	if len(params) == 0 {
		return "-"
	}
	parts := make([]string, len(params))
	for i, p := range params {
		parts[i] = shortType(p.Type)
	}
	return strings.Join(parts, ", ")
}

// ---- classes stats ----

type scanStats struct {
	ProjectClassCount         int    `json:"projectClassCount"`
	ProjectInterfaceCount     int    `json:"projectInterfaceCount"`
	ProjectAbstractClassCount int    `json:"projectAbstractClassCount"`
	ProjectEnumCount          int    `json:"projectEnumCount"`
	ProjectAnnotationCount    int    `json:"projectAnnotationCount"`
	ProjectMethodCount        int    `json:"projectMethodCount"`
	ProjectFieldCount         int    `json:"projectFieldCount"`
	LibraryClassCount         int    `json:"libraryClassCount"`
	JdkClassCount             int    `json:"jdkClassCount"`
	ClasspathEntryCount       int    `json:"classpathEntryCount"`
	ClasspathResolvedBy       string `json:"classpathResolvedBy"`
	ScanDurationMs            int    `json:"scanDurationMs"`
	ScannedAt                 string `json:"scannedAt"`
}

// ClassesStats renders `classes stats`.
func ClassesStats(w io.Writer, v any) error {
	s, err := decode[scanStats](v)
	if err != nil {
		return err
	}
	fmt.Fprintln(w, "Scan Statistics")
	fmt.Fprintln(w)
	itoa := strconv.Itoa
	return KVBlock(w, [][2]string{
		{"Project Classes:", itoa(s.ProjectClassCount)},
		{"  - Interfaces:", itoa(s.ProjectInterfaceCount)},
		{"  - Abstract Classes:", itoa(s.ProjectAbstractClassCount)},
		{"  - Enums:", itoa(s.ProjectEnumCount)},
		{"  - Annotations:", itoa(s.ProjectAnnotationCount)},
		{"Project Methods:", itoa(s.ProjectMethodCount)},
		{"Project Fields:", itoa(s.ProjectFieldCount)},
		{"Library Classes:", itoa(s.LibraryClassCount)},
		{"JDK Classes:", itoa(s.JdkClassCount)},
		{"Classpath Entries:", itoa(s.ClasspathEntryCount)},
		{"Resolved By:", dash(s.ClasspathResolvedBy)},
		{"Scan Duration:", itoa(s.ScanDurationMs) + "ms"},
		{"Scanned At:", dash(s.ScannedAt)},
	})
}

// ---- classes implementations ----

type implementationsResponse struct {
	TargetClass             string         `json:"targetClass"`
	DirectImplementations   []classSummary `json:"directImplementations"`
	IndirectImplementations []classSummary `json:"indirectImplementations"`
	TotalCount              int            `json:"totalCount"`
}

// Implementations renders `classes implementations`.
func Implementations(w io.Writer, v any) error {
	resp, err := decode[implementationsResponse](v)
	if err != nil {
		return err
	}
	fmt.Fprintf(w, "Implementations of %s\n", resp.TargetClass)
	fmt.Fprintf(w, "Total: %d (%d direct, %d indirect)\n\n",
		resp.TotalCount, len(resp.DirectImplementations), len(resp.IndirectImplementations))
	if resp.TotalCount == 0 {
		fmt.Fprintln(w, "No implementations found.")
		return nil
	}
	rows := make([][]string, 0, resp.TotalCount)
	for _, c := range resp.DirectImplementations {
		rows = append(rows, []string{c.Fqn, c.typeStr(), "yes", c.Source})
	}
	for _, c := range resp.IndirectImplementations {
		rows = append(rows, []string{c.Fqn, c.typeStr(), "no", c.Source})
	}
	return Table(w, []string{"Class", "Type", "Direct", "Source"}, rows)
}

// ---- classes dependencies ----

type depRef struct {
	ClassFqn       string `json:"classFqn"`
	DependencyType string `json:"dependencyType"`
	Location       string `json:"location"`
	Source         string `json:"source"`
}

type dependenciesResponse struct {
	TargetClass string   `json:"targetClass"`
	Incoming    []depRef `json:"incoming"`
	Outgoing    []depRef `json:"outgoing"`
}

// Dependencies renders `classes dependencies`.
func Dependencies(w io.Writer, v any) error {
	resp, err := decode[dependenciesResponse](v)
	if err != nil {
		return err
	}
	fmt.Fprintf(w, "Dependencies for %s\n\n", resp.TargetClass)

	section := func(title string, refs []depRef) error {
		fmt.Fprintf(w, "%s (%d):\n", title, len(refs))
		if len(refs) == 0 {
			fmt.Fprintln(w, "  (none)")
			return nil
		}
		rows := make([][]string, 0, len(refs))
		for _, r := range refs {
			rows = append(rows, []string{r.ClassFqn, r.DependencyType, dash(r.Location), r.Source})
		}
		return Table(w, []string{"Class", "Type", "Location", "Source"}, rows)
	}

	if err := section("Outgoing (this class depends on)", resp.Outgoing); err != nil {
		return err
	}
	fmt.Fprintln(w)
	return section("Incoming (classes depending on this)", resp.Incoming)
}

// ---- classes hierarchy ----

type hierarchyNode struct {
	ClassFqn    string          `json:"classFqn"`
	SimpleName  string          `json:"simpleName"`
	Source      string          `json:"source"`
	IsInterface bool            `json:"isInterface"`
	Parent      *hierarchyNode  `json:"parent"`
	Interfaces  []hierarchyNode `json:"interfaces"`
	Children    []hierarchyNode `json:"children"`
}

func (n hierarchyNode) nodeType() string {
	if n.IsInterface {
		return "interface"
	}
	return "class"
}

type hierarchyResponse struct {
	TargetClass string        `json:"targetClass"`
	Hierarchy   hierarchyNode `json:"hierarchy"`
}

// Hierarchy renders `classes hierarchy` as a tree.
func Hierarchy(w io.Writer, v any) error {
	resp, err := decode[hierarchyResponse](v)
	if err != nil {
		return err
	}
	h := resp.Hierarchy
	fmt.Fprintf(w, "Hierarchy for %s\n\n", resp.TargetClass)

	// Parent chain, root first.
	var chain []hierarchyNode
	for p := h.Parent; p != nil; p = p.Parent {
		chain = append([]hierarchyNode{*p}, chain...)
	}
	if len(chain) > 0 {
		fmt.Fprintln(w, "Parents:")
		indent := "  "
		for i, p := range chain {
			last := i == len(chain)-1
			fmt.Fprintf(w, "%s%s%s (%s)\n", indent, branch(last), p.ClassFqn, p.nodeType())
			indent += cont(last)
		}
		fmt.Fprintln(w)
	}

	fmt.Fprintf(w, "%s (%s)\n", h.ClassFqn, h.nodeType())

	if len(h.Interfaces) > 0 {
		fmt.Fprintln(w, "\nImplements:")
		for _, iface := range h.Interfaces {
			fmt.Fprintf(w, "  - %s\n", iface.ClassFqn)
		}
	}
	if len(h.Children) > 0 {
		fmt.Fprintf(w, "\nChildren (%d):\n", len(h.Children))
		printChildren(w, h.Children, "  ")
	}
	return nil
}

func printChildren(w io.Writer, children []hierarchyNode, indent string) {
	for i, c := range children {
		last := i == len(children)-1
		fmt.Fprintf(w, "%s%s%s (%s)\n", indent, branch(last), c.SimpleName, c.nodeType())
		if len(c.Children) > 0 {
			printChildren(w, c.Children, indent+cont(last))
		}
	}
}

func branch(last bool) string {
	if last {
		return "└── "
	}
	return "├── "
}

func cont(last bool) string {
	if last {
		return "    "
	}
	return "│   "
}
