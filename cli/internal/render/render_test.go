package render

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"strings"
	"testing"

	"github.com/charliek/codelens/cli/internal/client"
)

// render feeds raw JSON to a renderer and returns its output, failing on error.
// Renderers take io.Writer; a *bytes.Buffer satisfies it.
func render(t *testing.T, fn func(w io.Writer, v any) error, fixture string) string {
	t.Helper()
	var buf bytes.Buffer
	if err := fn(&buf, json.RawMessage(fixture)); err != nil {
		t.Fatalf("render returned error: %v", err)
	}
	return buf.String()
}

func mustContainAll(t *testing.T, got string, subs ...string) {
	t.Helper()
	for _, s := range subs {
		if !strings.Contains(got, s) {
			t.Errorf("output missing %q\n--- output ---\n%s", s, got)
		}
	}
}

func mustNotLookLikeJSON(t *testing.T, got string) {
	t.Helper()
	if strings.HasPrefix(strings.TrimSpace(got), "{") || strings.HasPrefix(strings.TrimSpace(got), "[") {
		t.Errorf("output looks like JSON, expected a table:\n%s", got)
	}
}

func TestShortType(t *testing.T) {
	cases := map[string]string{
		"ratpack.exec.Promise<java.lang.String>":  "Promise<String>",
		"java.util.List<sample.handlers.Service>": "List<Service>",
		"void":             "void",
		"int[]":            "int[]",
		"java.lang.String": "String",
	}
	for in, want := range cases {
		if got := shortType(in); got != want {
			t.Errorf("shortType(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestSimpleNameAndDash(t *testing.T) {
	if got := simpleName("a.b.C"); got != "C" {
		t.Errorf("simpleName = %q", got)
	}
	if got := simpleName("C"); got != "C" {
		t.Errorf("simpleName(bare) = %q", got)
	}
	if got := dash(""); got != "-" {
		t.Errorf("dash(empty) = %q", got)
	}
	if got := lineStr(0); got != "-" {
		t.Errorf("lineStr(0) = %q", got)
	}
	if got := lineStr(12); got != "12" {
		t.Errorf("lineStr(12) = %q", got)
	}
}

func TestClassesList(t *testing.T) {
	const fixture = `{
		"appliedFilter":{"packagePattern":null,"namePattern":null,"hasAnnotation":null,"extendsClass":null,"implementsInterface":null,"source":"PROJECT"},
		"classes":[{"fqn":"sample.Foo","simpleName":"Foo","packageName":"sample","source":"PROJECT","methodCount":3,"fieldCount":1,"isInterface":false,"isAnnotation":false,"isEnum":false,"isAbstract":false},
		{"fqn":"sample.Bar","simpleName":"Bar","packageName":"sample","source":"PROJECT","methodCount":0,"fieldCount":0,"isInterface":true,"isAnnotation":false,"isEnum":false,"isAbstract":false}],
		"page":0,"pageSize":50,"totalCount":2,"totalPages":1}`
	got := render(t, ClassesList, fixture)
	mustNotLookLikeJSON(t, got)
	mustContainAll(t, got, "Classes (1-2 of 2) | Filter: none", "Name", "Type", "Methods", "Foo", "Bar", "interface")
}

func TestClassesListFilterSummary(t *testing.T) {
	const fixture = `{"appliedFilter":{"packagePattern":"sample.*","namePattern":null,"hasAnnotation":"javax.inject.Singleton","extendsClass":null,"implementsInterface":null,"source":"PROJECT"},
		"classes":[{"fqn":"sample.Foo","simpleName":"Foo","packageName":"sample","source":"PROJECT","methodCount":1,"fieldCount":0,"isInterface":false,"isAnnotation":false,"isEnum":false,"isAbstract":false}],
		"page":0,"pageSize":50,"totalCount":1,"totalPages":1}`
	got := render(t, ClassesList, fixture)
	mustContainAll(t, got, "package=sample.*", "annotation=javax.inject.Singleton")
}

func TestClassesListEmpty(t *testing.T) {
	got := render(t, ClassesList, `{"classes":[],"page":0,"pageSize":50,"totalCount":0,"totalPages":0,"appliedFilter":{"source":"PROJECT"}}`)
	mustContainAll(t, got, "No classes found")
}

func TestClassesShow(t *testing.T) {
	const fixture = `{"classInfo":{
		"name":{"fqn":"sample.handlers.BlockingHandler","packageName":"sample.handlers","simpleName":"BlockingHandler"},
		"source":"PROJECT","visibility":"PUBLIC","superclass":null,
		"interfaces":["ratpack.handling.Handler"],
		"annotations":[{"type":"javax.inject.Singleton","parameters":{}}],
		"isAbstract":false,"isAnnotation":false,"isEnum":false,"isInterface":false,
		"methods":[
			{"name":"handle","returnType":"void","parameters":[{"name":"arg0","type":"ratpack.handling.Context"}],"visibility":"PUBLIC","isSynthetic":false},
			{"name":"lambda$handle$0","returnType":"java.lang.Object","parameters":[],"visibility":"PRIVATE","isSynthetic":true}],
		"fields":[{"name":"userService","type":"sample.handlers.UserService","visibility":"PRIVATE"}]}}`
	got := render(t, ClassesShow, fixture)
	mustNotLookLikeJSON(t, got)
	mustContainAll(t, got, "sample.handlers.BlockingHandler", "Package:", "Implements:", "ratpack.handling.Handler",
		"@Singleton", "Methods (1)", "handle", "Context", "Fields (1)", "userService", "UserService")
	if strings.Contains(got, "lambda$handle$0") {
		t.Errorf("synthetic method should be hidden:\n%s", got)
	}
}

func TestClassesStats(t *testing.T) {
	const fixture = `{"projectClassCount":13,"projectInterfaceCount":2,"projectAbstractClassCount":0,"projectEnumCount":0,
		"projectAnnotationCount":0,"projectMethodCount":42,"projectFieldCount":8,"libraryClassCount":17388,
		"jdkClassCount":83,"classpathEntryCount":89,"classpathResolvedBy":"Gradle Tooling API","scanDurationMs":120,"scannedAt":"2026-05-25T00:00:00Z"}`
	got := render(t, ClassesStats, fixture)
	mustContainAll(t, got, "Scan Statistics", "Project Classes:", "13", "Library Classes:", "17388", "120ms", "Gradle Tooling API")
}

func TestImplementations(t *testing.T) {
	const fixture = `{"targetClass":"com.google.inject.AbstractModule","totalCount":1,
		"directImplementations":[{"fqn":"sample.modules.AppModule","simpleName":"AppModule","packageName":"sample.modules","source":"PROJECT","methodCount":4,"fieldCount":0,"isInterface":false,"isAnnotation":false,"isEnum":false,"isAbstract":false}],
		"indirectImplementations":[]}`
	got := render(t, Implementations, fixture)
	mustContainAll(t, got, "Implementations of com.google.inject.AbstractModule", "1 direct", "sample.modules.AppModule", "yes")
}

func TestDependencies(t *testing.T) {
	const fixture = `{"targetClass":"sample.handlers.BlockingHandler",
		"incoming":[{"classFqn":"sample.api.UsersApi","dependencyType":"FIELD_TYPE","location":"blockingHandler","source":"PROJECT"}],
		"outgoing":[{"classFqn":"sample.handlers.UserService","dependencyType":"FIELD_TYPE","location":"userService","source":"PROJECT"}]}`
	got := render(t, Dependencies, fixture)
	mustContainAll(t, got, "Dependencies for sample.handlers.BlockingHandler", "Outgoing", "Incoming",
		"sample.handlers.UserService", "sample.api.UsersApi", "FIELD_TYPE")
}

func TestHierarchy(t *testing.T) {
	const fixture = `{"targetClass":"sample.handlers.BlockingHandler","hierarchy":{
		"classFqn":"sample.handlers.BlockingHandler","simpleName":"BlockingHandler","source":"PROJECT","isInterface":false,
		"parent":{"classFqn":"java.lang.Object","simpleName":"Object","source":"JDK","isInterface":false,"parent":null,"interfaces":[],"children":[]},
		"interfaces":[{"classFqn":"ratpack.handling.Handler","simpleName":"Handler","source":"LIBRARY","isInterface":true,"interfaces":[],"children":[]}],
		"children":[]}}`
	got := render(t, Hierarchy, fixture)
	mustContainAll(t, got, "Hierarchy for sample.handlers.BlockingHandler", "Parents:", "java.lang.Object",
		"Implements:", "ratpack.handling.Handler")
}

func TestMethodsSearch(t *testing.T) {
	const fixture = `{"methods":[
		{"classFqn":"sample.handlers.BlockingHandler","classSimpleName":"BlockingHandler","classSource":"PROJECT",
		 "method":{"name":"getUserName","returnType":"ratpack.exec.Promise<java.lang.String>","parameters":[{"name":"arg0","type":"java.lang.String"}],"visibility":"PUBLIC","isSynthetic":false}}],
		"page":0,"pageSize":50,"totalCount":1,"totalPages":1}`
	got := render(t, MethodsSearch, fixture)
	mustContainAll(t, got, "Methods (1-1 of 1)", "Class", "BlockingHandler", "getUserName", "Promise<String>", "String")
}

func TestCalls(t *testing.T) {
	const fixture = `{"fqn":"sample.api.UsersApi","methods":[
		{"methodName":"execute","descriptor":"(Lratpack/handling/Chain;)V","calls":[
			{"constantArgs":[{"kind":"STRING","value":":id"}],"descriptor":"(Ljava/lang/String;Lratpack/handling/Handler;)Lratpack/handling/Chain;","isInterface":true,"lineNumber":29,"methodName":"get","ownerType":"ratpack.handling.Chain"}]}]}`
	got := render(t, Calls, fixture)
	mustNotLookLikeJSON(t, got)
	mustContainAll(t, got, "Calls from sample.api.UsersApi", "execute", "Line", "Owner", "Chain", "get", ":id", "29")
}

func TestCallsEmpty(t *testing.T) {
	got := render(t, Calls, `{"fqn":"sample.Foo","methods":[]}`)
	mustContainAll(t, got, "No calls found")
}

func TestXref(t *testing.T) {
	const fixture = `{"typeFqn":"sample.handlers.UserService","totalCount":2,"totalPages":1,"page":0,"pageSize":50,
		"countsByKind":{"FIELD":1,"PARAM":1},"countsByPackage":{"sample.handlers":2},
		"references":[
			{"detail":"sample.handlers.UserService","fromFqn":"sample.handlers.AsyncHandler","fromSimpleName":"AsyncHandler","fromSource":"PROJECT","kind":"FIELD","lineNumber":null,"member":"userService"},
			{"detail":null,"fromFqn":"sample.handlers.AsyncHandler","fromSimpleName":"AsyncHandler","fromSource":"PROJECT","kind":"PARAM","lineNumber":null,"member":"<init>"}]}`
	got := render(t, Xref, fixture)
	mustNotLookLikeJSON(t, got)
	mustContainAll(t, got, "References to sample.handlers.UserService", "Total: 2", "FIELD=1", "PARAM=1",
		"Kind", "From", "AsyncHandler", "userService")
}

func TestDepsGraph(t *testing.T) {
	const fixture = `{"nodeCount":3,"edgeCount":2,"nodes":[
		{"fqn":"sample.handlers.UserService","inDegree":2,"outDegree":0},
		{"fqn":"sample.handlers.AsyncHandler","inDegree":0,"outDegree":1},
		{"fqn":"sample.api.UsersApi","inDegree":0,"outDegree":1}]}`
	got := render(t, DepsGraph, fixture)
	mustContainAll(t, got, "Graph: 3 nodes, 2 edges", "most depended-on", "UserService", "--json for the full graph")
}

func TestDepsGraphEmptyFallsBack(t *testing.T) {
	var buf bytes.Buffer
	err := DepsGraph(&buf, json.RawMessage(`{"nodeCount":0,"edgeCount":0,"nodes":[]}`))
	if !errors.Is(err, ErrFallback) {
		t.Fatalf("expected ErrFallback for empty graph, got %v", err)
	}
}

func TestDepsGraphDotFallsBack(t *testing.T) {
	var buf bytes.Buffer
	// DOT output arrives as []byte, not json.RawMessage → must fall back.
	err := DepsGraph(&buf, []byte("digraph G {}"))
	if !errors.Is(err, ErrFallback) {
		t.Fatalf("expected ErrFallback for []byte DOT, got %v", err)
	}
}

func TestFoundation(t *testing.T) {
	const fixture = `{"count":2,"foundationClasses":[
		{"fqn":"sample.handlers.UserService","simpleName":"UserService","packageName":"sample.handlers","dependentCount":4},
		{"fqn":"sample.handlers.NotificationService","simpleName":"NotificationService","packageName":"sample.handlers","dependentCount":2}]}`
	got := render(t, Foundation, fixture)
	mustContainAll(t, got, "Foundation classes (2)", "Class", "Dependents", "UserService", "4")
}

func TestAnnotationUsages(t *testing.T) {
	const fixture = `{"annotationFqn":"javax.inject.Singleton","totalCount":1,"usages":[
		{"fqn":"sample.api.UsersApi","simpleName":"UsersApi","packageName":"sample.api","source":"PROJECT","methodCount":7,"fieldCount":2,"isInterface":false,"isAnnotation":false,"isEnum":false,"isAbstract":false}]}`
	got := render(t, AnnotationUsages, fixture)
	mustContainAll(t, got, "Usages of @javax.inject.Singleton (1)", "UsersApi", "sample.api")
}

func TestSourceShow(t *testing.T) {
	const fixture = `{"source":{"content":"package sample;\n\npublic class Foo {}\n","filePath":"/tmp/Foo.java","fqn":"sample.Foo","language":"JAVA","lineCount":3,"isDecompiled":false,"sourceOrigin":"PROJECT_SOURCE"}}`
	got := render(t, SourceShow, fixture)
	mustContainAll(t, got, "sample.Foo", "File: /tmp/Foo.java", "JAVA | 3 lines", "public class Foo {}")
}

func TestSourceShowEmptyFallsBack(t *testing.T) {
	var buf bytes.Buffer
	err := SourceShow(&buf, json.RawMessage(`{"source":{"content":"","fqn":"sample.Foo"}}`))
	if !errors.Is(err, ErrFallback) {
		t.Fatalf("expected ErrFallback for empty source, got %v", err)
	}
}

func TestSourceMethod(t *testing.T) {
	const fixture = `{"methodSource":{"classFqn":"sample.Foo","methodName":"bar","signature":"void bar()","content":"void bar() {}\n","startLine":10,"endLine":12}}`
	got := render(t, SourceMethod, fixture)
	mustContainAll(t, got, "sample.Foo", "void bar()", "Lines 10-12", "void bar() {}")
}

func TestProject(t *testing.T) {
	const fixture = `{"classCount":13,"name":"sample-app","path":"/tmp/app","status":"READY","scannedAt":"2026-05-25T00:00:00Z"}`
	got := render(t, Project, fixture)
	mustContainAll(t, got, "Name:", "sample-app", "Status:", "READY", "Classes:", "13")
}

func TestBadInputFallsBack(t *testing.T) {
	var buf bytes.Buffer
	if err := ClassesList(&buf, json.RawMessage(`not json`)); !errors.Is(err, ErrFallback) {
		t.Fatalf("expected ErrFallback for malformed JSON, got %v", err)
	}
	if err := ClassesList(&buf, "a plain string, not RawMessage"); !errors.Is(err, ErrFallback) {
		t.Fatalf("expected ErrFallback for non-RawMessage, got %v", err)
	}
}

func TestLintCheckFile(t *testing.T) {
	resp := &client.LintFileResponse{
		FilePath:   "/tmp/Bad.kt",
		ErrorCount: 2,
		Errors: []client.LintError{
			{Line: 1, Col: 1, RuleID: "standard:no-wildcard-imports", Detail: "wildcard import"},
			{Line: 5, Col: 3, RuleID: "standard:indent", Detail: "unexpected indentation"},
		},
	}
	var buf bytes.Buffer
	if err := LintCheck(&buf, resp); err != nil {
		t.Fatal(err)
	}
	mustContainAll(t, buf.String(), "/tmp/Bad.kt", "1:1", "standard:no-wildcard-imports", "2 violation(s)")
}

func TestLintCheckProjectClean(t *testing.T) {
	resp := &client.LintProjectResponse{ProjectPath: "/tmp/app", FilesScanned: 3, FilesWithErrors: 0, TotalErrorCount: 0}
	var buf bytes.Buffer
	if err := LintCheck(&buf, resp); err != nil {
		t.Fatal(err)
	}
	mustContainAll(t, buf.String(), "Lint: /tmp/app", "Scanned 3 file(s)")
}

func TestLintFormatProject(t *testing.T) {
	resp := &client.FormatProjectResponse{ProjectPath: "/tmp/app", FilesScanned: 3, FilesWithChanges: 1, FilesFormatted: []string{"/tmp/app/Bad.kt"}}
	var buf bytes.Buffer
	if err := LintFormat(&buf, resp); err != nil {
		t.Fatal(err)
	}
	mustContainAll(t, buf.String(), "Format: /tmp/app", "1 with changes", "/tmp/app/Bad.kt")
}

// On a dry run the server returns formattedContent and does not write the file;
// the table must show that preview, not just claim the file was "formatted".
func TestLintFormatFileDryRun(t *testing.T) {
	formatted := "package sample\n\nfun ok() {}\n"
	resp := &client.FormatFileResponse{FilePath: "/tmp/Bad.kt", FormattedContent: &formatted, HasChanges: true}
	var buf bytes.Buffer
	if err := LintFormat(&buf, resp); err != nil {
		t.Fatal(err)
	}
	out := buf.String()
	mustContainAll(t, out, "/tmp/Bad.kt", "dry run", "not written", "fun ok() {}")
	if strings.Contains(out, ": formatted") {
		t.Errorf("dry run must not claim the file was formatted/written:\n%s", out)
	}
}

// When the file is written (not a dry run), formattedContent is nil and only a
// status line prints.
func TestLintFormatFileWritten(t *testing.T) {
	resp := &client.FormatFileResponse{FilePath: "/tmp/Bad.kt", FormattedContent: nil, HasChanges: true}
	var buf bytes.Buffer
	if err := LintFormat(&buf, resp); err != nil {
		t.Fatal(err)
	}
	mustContainAll(t, buf.String(), "/tmp/Bad.kt: formatted")
}

func TestLintWrongTypeFallsBack(t *testing.T) {
	var buf bytes.Buffer
	if err := LintCheck(&buf, json.RawMessage(`{}`)); !errors.Is(err, ErrFallback) {
		t.Fatalf("expected ErrFallback for unexpected lint type, got %v", err)
	}
}
