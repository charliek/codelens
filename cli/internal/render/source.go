package render

import (
	"fmt"
	"io"
	"strings"
)

type sourceInfo struct {
	Content      string `json:"content"`
	FilePath     string `json:"filePath"`
	Fqn          string `json:"fqn"`
	Language     string `json:"language"`
	LineCount    int    `json:"lineCount"`
	IsDecompiled bool   `json:"isDecompiled"`
	SourceOrigin string `json:"sourceOrigin"`
}

type sourceResponse struct {
	Source sourceInfo `json:"source"`
}

// SourceShow renders `source show <fqn>` as the actual source code with a short
// header. An empty body falls back to JSON.
func SourceShow(w io.Writer, v any) error {
	resp, err := decode[sourceResponse](v)
	if err != nil {
		return err
	}
	s := resp.Source
	if s.Content == "" {
		return ErrFallback
	}
	fmt.Fprintln(w, s.Fqn)
	if s.FilePath != "" {
		fmt.Fprintf(w, "File: %s\n", s.FilePath)
	}
	fmt.Fprintf(w, "%s\n\n", sourceMeta(s.Language, s.LineCount, s.IsDecompiled))
	writeCode(w, s.Content)
	return nil
}

type methodSourceInfo struct {
	ClassFqn      string `json:"classFqn"`
	MethodName    string `json:"methodName"`
	Signature     string `json:"signature"`
	Content       string `json:"content"`
	StartLine     int    `json:"startLine"`
	EndLine       int    `json:"endLine"`
	ContextBefore string `json:"contextBefore"`
	ContextAfter  string `json:"contextAfter"`
}

type methodSourceResponse struct {
	MethodSource methodSourceInfo `json:"methodSource"`
}

// SourceMethod renders `source method <fqn> <method>` as the method's source
// code with a header (and any requested context lines). Empty body → JSON.
func SourceMethod(w io.Writer, v any) error {
	resp, err := decode[methodSourceResponse](v)
	if err != nil {
		return err
	}
	m := resp.MethodSource
	if m.Content == "" {
		return ErrFallback
	}
	fmt.Fprintln(w, m.ClassFqn)
	if m.Signature != "" {
		fmt.Fprintln(w, m.Signature)
	}
	fmt.Fprintf(w, "Lines %d-%d\n\n", m.StartLine, m.EndLine)
	if m.ContextBefore != "" {
		writeCode(w, m.ContextBefore)
	}
	writeCode(w, m.Content)
	if m.ContextAfter != "" {
		writeCode(w, m.ContextAfter)
	}
	return nil
}

func sourceMeta(language string, lineCount int, decompiled bool) string {
	meta := language
	if lineCount > 0 {
		meta += fmt.Sprintf(" | %d lines", lineCount)
	}
	if decompiled {
		meta += " | decompiled"
	}
	return meta
}

// writeCode writes a code block verbatim, guaranteeing a trailing newline.
func writeCode(w io.Writer, code string) {
	fmt.Fprint(w, code)
	if !strings.HasSuffix(code, "\n") {
		fmt.Fprintln(w)
	}
}
