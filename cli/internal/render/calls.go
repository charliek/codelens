package render

import (
	"fmt"
	"io"
	"strings"
)

type callArg struct {
	Kind  string `json:"kind"`
	Value any    `json:"value"`
}

type callSite struct {
	OwnerType    string    `json:"ownerType"`
	MethodName   string    `json:"methodName"`
	Descriptor   string    `json:"descriptor"`
	LineNumber   int       `json:"lineNumber"`
	IsInterface  bool      `json:"isInterface"`
	ConstantArgs []callArg `json:"constantArgs"`
}

type callMethod struct {
	MethodName string     `json:"methodName"`
	Descriptor string     `json:"descriptor"`
	Calls      []callSite `json:"calls"`
}

type callsResponse struct {
	Fqn     string       `json:"fqn"`
	Methods []callMethod `json:"methods"`
}

// Calls renders `calls <fqn>`: one table of outgoing call sites per method.
func Calls(w io.Writer, v any) error {
	resp, err := decode[callsResponse](v)
	if err != nil {
		return err
	}
	total := 0
	for _, m := range resp.Methods {
		total += len(m.Calls)
	}
	fmt.Fprintf(w, "Calls from %s\n", resp.Fqn)
	if total == 0 {
		fmt.Fprintln(w, "\nNo calls found.")
		return nil
	}
	for _, m := range resp.Methods {
		fmt.Fprintf(w, "\n%s%s\n", m.MethodName, m.Descriptor)
		if len(m.Calls) == 0 {
			fmt.Fprintln(w, "  (no calls)")
			continue
		}
		rows := make([][]string, 0, len(m.Calls))
		for _, c := range m.Calls {
			rows = append(rows, []string{
				lineStr(c.LineNumber),
				simpleName(c.OwnerType),
				c.MethodName,
				argList(c.ConstantArgs),
			})
		}
		if err := Table(w, []string{"Line", "Owner", "Method", "Args"}, rows); err != nil {
			return err
		}
	}
	return nil
}

// argList joins the constant arguments captured at a call site, or "-".
func argList(args []callArg) string {
	if len(args) == 0 {
		return "-"
	}
	parts := make([]string, len(args))
	for i, a := range args {
		parts[i] = fmt.Sprintf("%v", a.Value)
	}
	return strings.Join(parts, ", ")
}
