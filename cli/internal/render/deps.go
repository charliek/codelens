package render

import (
	"fmt"
	"io"
	"sort"
	"strconv"
)

type graphNode struct {
	Fqn       string `json:"fqn"`
	InDegree  int    `json:"inDegree"`
	OutDegree int    `json:"outDegree"`
}

type graphResponse struct {
	NodeCount int         `json:"nodeCount"`
	EdgeCount int         `json:"edgeCount"`
	Nodes     []graphNode `json:"nodes"`
}

// graphTopN bounds the "most depended-on" preview in the table view; the full
// graph is always available via --json or --format dot.
const graphTopN = 15

// DepsGraph renders the project dependency graph as a summary plus a top-N
// "most depended-on" table. DOT bytes and the empty graph fall back to JSON.
func DepsGraph(w io.Writer, v any) error {
	resp, err := decode[graphResponse](v)
	if err != nil {
		return err
	}
	if resp.NodeCount == 0 {
		return ErrFallback
	}
	fmt.Fprintf(w, "Graph: %d nodes, %d edges\n\n", resp.NodeCount, resp.EdgeCount)

	nodes := append([]graphNode(nil), resp.Nodes...)
	sort.SliceStable(nodes, func(i, j int) bool {
		if nodes[i].InDegree != nodes[j].InDegree {
			return nodes[i].InDegree > nodes[j].InDegree
		}
		return nodes[i].Fqn < nodes[j].Fqn
	})
	if len(nodes) > graphTopN {
		nodes = nodes[:graphTopN]
	}

	fmt.Fprintln(w, "Top classes by in-degree (most depended-on):")
	rows := make([][]string, 0, len(nodes))
	for _, n := range nodes {
		rows = append(rows, []string{simpleName(n.Fqn), strconv.Itoa(n.InDegree), strconv.Itoa(n.OutDegree)})
	}
	if err := Table(w, []string{"Class", "In", "Out"}, rows); err != nil {
		return err
	}
	fmt.Fprintln(w, "\nUse --json for the full graph, or --format dot for Graphviz.")
	return nil
}

type foundationClass struct {
	Fqn            string `json:"fqn"`
	SimpleName     string `json:"simpleName"`
	PackageName    string `json:"packageName"`
	DependentCount int    `json:"dependentCount"`
}

type foundationResponse struct {
	Count             int               `json:"count"`
	FoundationClasses []foundationClass `json:"foundationClasses"`
}

// Foundation renders `deps foundation`: the most depended-on classes by
// in-degree.
func Foundation(w io.Writer, v any) error {
	resp, err := decode[foundationResponse](v)
	if err != nil {
		return err
	}
	fmt.Fprintf(w, "Foundation classes (%d)\n\n", resp.Count)
	if resp.Count == 0 {
		fmt.Fprintln(w, "No foundation classes found.")
		return nil
	}
	classes := append([]foundationClass(nil), resp.FoundationClasses...)
	sort.SliceStable(classes, func(i, j int) bool {
		if classes[i].DependentCount != classes[j].DependentCount {
			return classes[i].DependentCount > classes[j].DependentCount
		}
		return classes[i].Fqn < classes[j].Fqn
	})
	rows := make([][]string, 0, len(classes))
	for _, c := range classes {
		rows = append(rows, []string{c.SimpleName, c.PackageName, strconv.Itoa(c.DependentCount)})
	}
	return Table(w, []string{"Class", "Package", "Dependents"}, rows)
}
