package client

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
)

// capture records every request the test server receives so individual cases
// can assert on path / query / body. Equivalent to test_client.py's _Capture.
type capture struct {
	requests []recordedRequest
}

type recordedRequest struct {
	Method string
	Path   string
	Query  url.Values
	RawURL string // raw URL string as received
	Body   []byte
}

func (c *capture) last() recordedRequest {
	if len(c.requests) == 0 {
		panic("no request captured")
	}
	return c.requests[len(c.requests)-1]
}

// newTestClient spins up an httptest.Server that records every request
// and returns the customary 200 + {"ok": true} body. Individual cases that
// need a specific response can use newTestClientWith.
func newTestClient(t *testing.T) (*Client, *capture, func()) {
	return newTestClientWith(t, func(_ *http.Request) (status int, body []byte) {
		return 200, []byte(`{"ok": true}`)
	})
}

func newTestClientWith(t *testing.T, respond func(*http.Request) (int, []byte)) (*Client, *capture, func()) {
	t.Helper()
	cap := &capture{}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		cap.requests = append(cap.requests, recordedRequest{
			Method: r.Method,
			Path:   r.URL.Path,
			Query:  r.URL.Query(),
			RawURL: r.RequestURI,
			Body:   body,
		})
		status, b := respond(r)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_, _ = w.Write(b)
	}))

	u, _ := url.Parse(srv.URL)
	c := &Client{BaseURL: srv.URL, HTTP: srv.Client()}
	_ = u
	return c, cap, srv.Close
}

func ctx() context.Context { return context.Background() }

// ====================== admin ======================

func TestAdmin_Health(t *testing.T) {
	c, cap, done := newTestClient(t)
	defer done()
	if _, err := c.Health(ctx()); err != nil {
		t.Fatal(err)
	}
	if got := cap.last(); got.Method != "GET" || got.Path != "/admin/health" {
		t.Errorf("got %v", got)
	}
}

func TestAdmin_Ready(t *testing.T) {
	c, cap, done := newTestClient(t)
	defer done()
	if _, err := c.Ready(ctx()); err != nil {
		t.Fatal(err)
	}
	if cap.last().Path != "/admin/ready" {
		t.Errorf("path = %s", cap.last().Path)
	}
}

func TestAdmin_Info(t *testing.T) {
	c, cap, done := newTestClient(t)
	defer done()
	if _, err := c.Info(ctx()); err != nil {
		t.Fatal(err)
	}
	if cap.last().Path != "/admin/info" {
		t.Errorf("path = %s", cap.last().Path)
	}
}

func TestAdmin_Project(t *testing.T) {
	c, cap, done := newTestClient(t)
	defer done()
	if _, err := c.Project(ctx()); err != nil {
		t.Fatal(err)
	}
	if cap.last().Path != "/api/v1/project" {
		t.Errorf("path = %s", cap.last().Path)
	}
}

func TestAdmin_Refresh_IsPOST(t *testing.T) {
	c, cap, done := newTestClient(t)
	defer done()
	if _, err := c.Refresh(ctx()); err != nil {
		t.Fatal(err)
	}
	if got := cap.last(); got.Method != "POST" || got.Path != "/api/v1/project/refresh" {
		t.Errorf("got %+v", got)
	}
}

func TestAdmin_Stats(t *testing.T) {
	c, cap, done := newTestClient(t)
	defer done()
	if _, err := c.Stats(ctx()); err != nil {
		t.Fatal(err)
	}
	if cap.last().Path != "/api/v1/stats" {
		t.Errorf("path = %s", cap.last().Path)
	}
}

// ====================== classes ======================

func TestClasses_ListPaginationDefaults(t *testing.T) {
	c, cap, done := newTestClient(t)
	defer done()
	if _, err := c.ListClasses(ctx(), ListClassesFilter{}); err != nil {
		t.Fatal(err)
	}
	q := cap.last().Query
	if q.Get("page") != "0" || q.Get("size") != "50" {
		t.Errorf("default pagination wrong: page=%q size=%q", q.Get("page"), q.Get("size"))
	}
}

func TestClasses_ListAllFiltersDocumentedQueryParamNames(t *testing.T) {
	c, cap, done := newTestClient(t)
	defer done()
	_, err := c.ListClasses(ctx(), ListClassesFilter{
		Package:          "com.example.*",
		Name:             "*Handler",
		Annotation:       "javax.inject.Singleton",
		Extends:          "com.example.BaseHandler",
		Implements:       "com.example.api.RequestHandler",
		InterfacesOnly:   true,
		IncludeLibraries: true,
		Page:             2,
		Size:             25,
	})
	if err != nil {
		t.Fatal(err)
	}
	q := cap.last().Query
	checks := map[string]string{
		"package":          "com.example.*",
		"name":             "*Handler",
		"annotation":       "javax.inject.Singleton",
		"extends":          "com.example.BaseHandler",
		"implements":       "com.example.api.RequestHandler",
		"interfaces":       "true", // lowercase string, not JSON bool
		"includeLibraries": "true",
		"page":             "2",
		"size":             "25",
	}
	for k, v := range checks {
		if got := q.Get(k); got != v {
			t.Errorf("query[%q] = %q, want %q", k, got, v)
		}
	}
}

func TestClasses_GetURLEncodesFQNDollarSign(t *testing.T) {
	c, cap, done := newTestClient(t)
	defer done()
	if _, err := c.GetClass(ctx(), "com.example.Outer$Inner"); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(cap.last().RawURL, "%24") {
		t.Errorf("URL must percent-encode $ as %%24; got %s", cap.last().RawURL)
	}
}

func TestClasses_GetFQNStaysSingleSegment(t *testing.T) {
	c, cap, done := newTestClient(t)
	defer done()
	if _, err := c.GetClass(ctx(), "com.example.UserHandler"); err != nil {
		t.Fatal(err)
	}
	// Path should start with /api/v1/classes/ and have no further slashes.
	p := cap.last().Path
	const prefix = "/api/v1/classes/"
	if !strings.HasPrefix(p, prefix) {
		t.Fatalf("path = %s", p)
	}
	suffix := p[len(prefix):]
	if strings.Contains(suffix, "/") {
		t.Errorf("FQN must be a single path segment; got %q", p)
	}
}

func TestClasses_ImplementationsOnlySendsParamWhenTrue(t *testing.T) {
	c, cap, done := newTestClient(t)
	defer done()
	if _, err := c.GetImplementations(ctx(), "com.example.api.RequestHandler", false); err != nil {
		t.Fatal(err)
	}
	if cap.last().RawURL != "/api/v1/implementations/com.example.api.RequestHandler" {
		t.Errorf("false case should have no query string; got %s", cap.last().RawURL)
	}
	if _, err := c.GetImplementations(ctx(), "com.example.api.RequestHandler", true); err != nil {
		t.Fatal(err)
	}
	if got := cap.last().Query.Get("includeLibraries"); got != "true" {
		t.Errorf("true case missing param; got %q", got)
	}
}

func TestClasses_Hierarchy(t *testing.T) {
	c, cap, done := newTestClient(t)
	defer done()
	if _, err := c.GetHierarchy(ctx(), "com.example.UserHandler"); err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(cap.last().Path, "/api/v1/hierarchy/") {
		t.Errorf("path = %s", cap.last().Path)
	}
}

func TestClasses_Dependencies(t *testing.T) {
	c, cap, done := newTestClient(t)
	defer done()
	if _, err := c.GetDependencies(ctx(), "com.example.UserHandler", false); err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(cap.last().Path, "/api/v1/dependencies/") {
		t.Errorf("path = %s", cap.last().Path)
	}
}

func TestClasses_AnnotationUsages(t *testing.T) {
	c, cap, done := newTestClient(t)
	defer done()
	if _, err := c.GetAnnotationUsages(ctx(), "javax.inject.Singleton",
		AnnotationUsagesFilter{Scope: "method", IncludeLibraries: true, Page: 1, Size: 10}); err != nil {
		t.Fatal(err)
	}
	got := cap.last()
	if !strings.HasPrefix(got.Path, "/api/v1/annotations/usages/") {
		t.Errorf("path = %s", got.Path)
	}
	q := got.Query
	if q.Get("scope") != "method" {
		t.Errorf("scope = %q, want method", q.Get("scope"))
	}
	if q.Get("page") != "1" || q.Get("size") != "10" {
		t.Errorf("pagination = page %q size %q", q.Get("page"), q.Get("size"))
	}
	if q.Get("includeLibraries") != "true" {
		t.Errorf("includeLibraries = %q, want true", q.Get("includeLibraries"))
	}
}

func TestClasses_AnnotationUsagesDefaultsOmitScope(t *testing.T) {
	c, cap, done := newTestClient(t)
	defer done()
	// An empty Scope (a direct API caller) must NOT send a scope param; the server
	// then defaults to ALL. The CLI command always sets a scope, so this only
	// exercises the omit path.
	if _, err := c.GetAnnotationUsages(ctx(), "javax.inject.Singleton", AnnotationUsagesFilter{}); err != nil {
		t.Fatal(err)
	}
	q := cap.last().Query
	if q.Has("scope") {
		t.Errorf("scope should be omitted when empty, got %q", q.Get("scope"))
	}
	if q.Get("page") != "0" || q.Get("size") != "50" {
		t.Errorf("default pagination wrong: page=%q size=%q", q.Get("page"), q.Get("size"))
	}
}

// ====================== methods ======================

func TestMethods_SearchPaginationDefaults(t *testing.T) {
	c, cap, done := newTestClient(t)
	defer done()
	if _, err := c.SearchMethods(ctx(), SearchMethodsFilter{}); err != nil {
		t.Fatal(err)
	}
	q := cap.last().Query
	if q.Get("page") != "0" || q.Get("size") != "50" {
		t.Errorf("default pagination wrong")
	}
}

func TestMethods_SearchUsesCamelCaseQueryNames(t *testing.T) {
	c, cap, done := newTestClient(t)
	defer done()
	_, err := c.SearchMethods(ctx(), SearchMethodsFilter{
		Name:             "get*",
		ReturnType:       "com.example.Result",
		Annotation:       "javax.inject.Inject",
		InClass:          "com.example.UserHandler",
		InPackage:        "com.example.*",
		IncludeLibraries: true,
	})
	if err != nil {
		t.Fatal(err)
	}
	q := cap.last().Query
	for k, want := range map[string]string{
		"name":             "get*",
		"returnType":       "com.example.Result",
		"annotation":       "javax.inject.Inject",
		"inClass":          "com.example.UserHandler",
		"inPackage":        "com.example.*",
		"includeLibraries": "true",
	} {
		if got := q.Get(k); got != want {
			t.Errorf("query[%q]=%q want %q", k, got, want)
		}
	}
}

// ====================== calls ======================

func TestCalls_NoMethodHitsClassEndpoint(t *testing.T) {
	c, cap, done := newTestClient(t)
	defer done()
	if _, err := c.GetCalls(ctx(), "com.example.UsersApi", "", "", "", ""); err != nil {
		t.Fatal(err)
	}
	// FQN is a single path segment (like the sibling class endpoints); no query.
	if cap.last().RawURL != "/api/v1/calls/com.example.UsersApi" {
		t.Errorf("unexpected URL: %s", cap.last().RawURL)
	}
}

func TestCalls_FQNStaysSingleSegment(t *testing.T) {
	c, cap, done := newTestClient(t)
	defer done()
	if _, err := c.GetCalls(ctx(), "com.example.Outer$Inner", "", "", "", ""); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(cap.last().RawURL, "%24") {
		t.Errorf("URL must percent-encode $ as %%24; got %s", cap.last().RawURL)
	}
	const prefix = "/api/v1/calls/"
	suffix := cap.last().Path[len(prefix):]
	if strings.Contains(suffix, "/") {
		t.Errorf("FQN must be a single path segment; got %q", cap.last().Path)
	}
}

func TestCalls_MethodIsQueryParam(t *testing.T) {
	c, cap, done := newTestClient(t)
	defer done()
	if _, err := c.GetCalls(ctx(), "com.example.UsersApi", "execute", "", "", ""); err != nil {
		t.Fatal(err)
	}
	if cap.last().Path != "/api/v1/calls/com.example.UsersApi" {
		t.Errorf("unexpected path: %s", cap.last().Path)
	}
	if got := cap.last().Query.Get("method"); got != "execute" {
		t.Errorf("method query = %q", got)
	}
}

func TestCalls_DescriptorOnlySentWithMethod(t *testing.T) {
	c, cap, done := newTestClient(t)
	defer done()
	// descriptor without a method must be dropped (the whole-class view ignores it).
	if _, err := c.GetCalls(ctx(), "com.example.UsersApi", "", "(Lcom/example/api/Chain;)V", "", ""); err != nil {
		t.Fatal(err)
	}
	if cap.last().RawURL != "/api/v1/calls/com.example.UsersApi" {
		t.Errorf("descriptor must be dropped without a method; got %s", cap.last().RawURL)
	}
	// with a method, descriptor rides along as a query param.
	if _, err := c.GetCalls(ctx(), "com.example.UsersApi", "execute", "(Lcom/example/api/Chain;)V", "", ""); err != nil {
		t.Fatal(err)
	}
	if got := cap.last().Query.Get("descriptor"); got != "(Lcom/example/api/Chain;)V" {
		t.Errorf("descriptor query = %q", got)
	}
}

func TestCalls_InMethodsFiltersAreQueryParams(t *testing.T) {
	c, cap, done := newTestClient(t)
	defer done()
	// The enclosing-method filters ride along as query params, independent of
	// --method, and compose with each other.
	if _, err := c.GetCalls(
		ctx(), "com.example.UsersApi", "", "",
		"reactor.core.publisher.Mono",
		"org.springframework.web.bind.annotation.GetMapping",
	); err != nil {
		t.Fatal(err)
	}
	if got := cap.last().Query.Get("inMethodsReturning"); got != "reactor.core.publisher.Mono" {
		t.Errorf("inMethodsReturning query = %q", got)
	}
	if got := cap.last().Query.Get("inMethodsAnnotated"); got != "org.springframework.web.bind.annotation.GetMapping" {
		t.Errorf("inMethodsAnnotated query = %q", got)
	}
	// Absent when unset: no stray query string.
	if _, err := c.GetCalls(ctx(), "com.example.UsersApi", "", "", "", ""); err != nil {
		t.Fatal(err)
	}
	if cap.last().RawURL != "/api/v1/calls/com.example.UsersApi" {
		t.Errorf("filters must be absent when unset; got %s", cap.last().RawURL)
	}
}

// ====================== xref ======================

func TestXref_DefaultPaginationAndSingleSegment(t *testing.T) {
	c, cap, done := newTestClient(t)
	defer done()
	if _, err := c.GetXref(ctx(), "javax.sql.DataSource", XrefFilter{}); err != nil {
		t.Fatal(err)
	}
	if cap.last().Path != "/api/v1/xref/javax.sql.DataSource" {
		t.Errorf("unexpected path: %s", cap.last().Path)
	}
	q := cap.last().Query
	if q.Get("page") != "0" || q.Get("size") != "50" {
		t.Errorf("expected default page=0 size=50; got page=%q size=%q", q.Get("page"), q.Get("size"))
	}
}

func TestXref_ForwardsAllFilters(t *testing.T) {
	c, cap, done := newTestClient(t)
	defer done()
	_, err := c.GetXref(ctx(), "com.example.Blocking", XrefFilter{
		IncludeLibraries:  true,
		Kind:              "CALL_RECEIVER",
		ScopeImplementing: "com.example.api.RequestHandler",
		Page:              2,
		Size:              10,
	})
	if err != nil {
		t.Fatal(err)
	}
	q := cap.last().Query
	for k, want := range map[string]string{
		"includeLibraries":  "true",
		"kind":              "CALL_RECEIVER",
		"scopeImplementing": "com.example.api.RequestHandler",
		"page":              "2",
		"size":              "10",
	} {
		if got := q.Get(k); got != want {
			t.Errorf("query[%q]=%q want %q", k, got, want)
		}
	}
}

// ====================== ktlint (POST + typed) ======================

func TestKtlint_LintFile_BodyHasFilePath(t *testing.T) {
	c, cap, done := newTestClientWith(t, func(_ *http.Request) (int, []byte) {
		return 200, []byte(`{"filePath": "/tmp/src/Foo.kt", "errors": [], "errorCount": 0, "durationMs": 12}`)
	})
	defer done()
	if _, err := c.LintFile(ctx(), "/tmp/src/Foo.kt"); err != nil {
		t.Fatal(err)
	}
	r := cap.last()
	if r.Method != "POST" || r.Path != "/api/v1/ktlint/lint/file" {
		t.Errorf("method/path: %s %s", r.Method, r.Path)
	}
	var got map[string]any
	if err := json.Unmarshal(r.Body, &got); err != nil {
		t.Fatal(err)
	}
	if got["filePath"] != "/tmp/src/Foo.kt" {
		t.Errorf("body = %v", got)
	}
}

func TestKtlint_LintProject_OmitsPatternWhenAbsent(t *testing.T) {
	c, cap, done := newTestClientWith(t, func(_ *http.Request) (int, []byte) {
		return 200, []byte(`{"projectPath":"/tmp","fileResults":[],"filesScanned":0,"filesWithErrors":0,"totalErrorCount":0,"durationMs":1}`)
	})
	defer done()
	if _, err := c.LintProject(ctx(), "", false); err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	if err := json.Unmarshal(cap.last().Body, &got); err != nil {
		t.Fatal(err)
	}
	if len(got) != 1 || got["includeTests"] != false {
		t.Errorf("body = %v (expected only includeTests=false)", got)
	}
}

func TestKtlint_FormatFile_BodyIncludesWriteToFile(t *testing.T) {
	c, cap, done := newTestClientWith(t, func(_ *http.Request) (int, []byte) {
		return 200, []byte(`{"filePath":"/tmp/x.kt","formattedContent":null,"hasChanges":false,"remainingErrors":[],"durationMs":3}`)
	})
	defer done()
	if _, err := c.FormatFile(ctx(), "/tmp/x.kt", true); err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	if err := json.Unmarshal(cap.last().Body, &got); err != nil {
		t.Fatal(err)
	}
	if got["filePath"] != "/tmp/x.kt" || got["writeToFile"] != true {
		t.Errorf("body = %v", got)
	}
}

// Gap fill: FormatProject was not covered by test_client.py.
func TestKtlint_FormatProject_BodyShape(t *testing.T) {
	c, cap, done := newTestClientWith(t, func(_ *http.Request) (int, []byte) {
		return 200, []byte(`{"projectPath":"/tmp","filesFormatted":[],"filesScanned":0,"filesWithChanges":0,"durationMs":1}`)
	})
	defer done()
	if _, err := c.FormatProject(ctx(), "*.kt", true, true); err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	if err := json.Unmarshal(cap.last().Body, &got); err != nil {
		t.Fatal(err)
	}
	for k, want := range map[string]any{
		"includeTests": true,
		"dryRun":       true,
		"pattern":      "*.kt",
	} {
		if got[k] != want {
			t.Errorf("body[%q] = %v, want %v", k, got[k], want)
		}
	}
}

// Server's LintProjectResponse.fileResults entries are FileLintResult (no
// durationMs). Reusing LintFileResponse would re-emit "durationMs":0 per
// file and corrupt the contract — guard against that.
func TestKtlint_LintProject_ResponseRoundTrip_NoPerFileDurationMs(t *testing.T) {
	wireResponse := `{
		"projectPath": "/tmp",
		"fileResults": [
			{
				"filePath": "/tmp/Foo.kt",
				"errors": [{"line": 1, "col": 1, "ruleId": "no-wildcard-imports", "detail": "wildcard", "canBeAutoCorrected": true}],
				"errorCount": 1
			}
		],
		"filesScanned": 5,
		"filesWithErrors": 1,
		"totalErrorCount": 1,
		"durationMs": 42
	}`
	c, _, done := newTestClientWith(t, func(_ *http.Request) (int, []byte) {
		return 200, []byte(wireResponse)
	})
	defer done()
	resp, err := c.LintProject(ctx(), "", true)
	if err != nil {
		t.Fatal(err)
	}
	out, err := json.Marshal(resp)
	if err != nil {
		t.Fatal(err)
	}
	// Decode into a generic map to inspect per-file entry shape.
	var m map[string]any
	if err := json.Unmarshal(out, &m); err != nil {
		t.Fatal(err)
	}
	files, _ := m["fileResults"].([]any)
	if len(files) != 1 {
		t.Fatalf("expected 1 file result; got %d", len(files))
	}
	first, _ := files[0].(map[string]any)
	if _, has := first["durationMs"]; has {
		t.Errorf("per-file result must NOT have durationMs; got %v", first)
	}
	if first["errorCount"] != float64(1) {
		t.Errorf("errorCount lost: %v", first["errorCount"])
	}
}

// Server's FormatProjectResponse uses filesFormatted: []string, NOT a
// per-file result list. Reusing FormatFileResponse here would drop the
// formatted file names — guard against that.
func TestKtlint_FormatProject_ResponseRoundTrip_FilesFormattedSurvives(t *testing.T) {
	wireResponse := `{
		"projectPath": "/tmp",
		"filesFormatted": ["/tmp/a.kt", "/tmp/b.kt"],
		"filesScanned": 5,
		"filesWithChanges": 2,
		"durationMs": 17
	}`
	c, _, done := newTestClientWith(t, func(_ *http.Request) (int, []byte) {
		return 200, []byte(wireResponse)
	})
	defer done()
	resp, err := c.FormatProject(ctx(), "", true, false)
	if err != nil {
		t.Fatal(err)
	}
	if len(resp.FilesFormatted) != 2 || resp.FilesFormatted[0] != "/tmp/a.kt" {
		t.Errorf("FilesFormatted lost: %v", resp.FilesFormatted)
	}
	out, _ := json.Marshal(resp)
	var m map[string]any
	_ = json.Unmarshal(out, &m)
	if _, has := m["filesFormatted"]; !has {
		t.Errorf("filesFormatted dropped from re-emitted JSON: %s", out)
	}
	if _, has := m["fileResults"]; has {
		t.Errorf("spurious fileResults appeared in re-emitted JSON: %s", out)
	}
}

// ====================== source ======================

func TestSource_Get(t *testing.T) {
	c, cap, done := newTestClient(t)
	defer done()
	if _, err := c.GetSource(ctx(), "com.example.UserHandler"); err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(cap.last().Path, "/api/v1/source/") {
		t.Errorf("path = %s", cap.last().Path)
	}
}

func TestSource_MethodWithParamTypesCommaJoined(t *testing.T) {
	c, cap, done := newTestClient(t)
	defer done()
	_, err := c.GetMethodSource(ctx(),
		"com.example.UserHandler",
		"handle",
		[]string{"com.example.api.Context", "java.lang.String"},
		3,
	)
	if err != nil {
		t.Fatal(err)
	}
	r := cap.last()
	if !strings.Contains(r.Path, "/method/") || !strings.HasPrefix(r.Path, "/api/v1/source/") {
		t.Errorf("path = %s", r.Path)
	}
	// Comma-joined, NOT repeated keys. Locked at test_client.py:375.
	if got := r.Query.Get("paramTypes"); got != "com.example.api.Context,java.lang.String" {
		t.Errorf("paramTypes wrong: %q", got)
	}
	if got := r.Query.Get("context"); got != "3" {
		t.Errorf("context wrong: %q", got)
	}
}

// Gap fill: method name with special chars like <init> or $lambda$0.
func TestSource_MethodNameEscaped(t *testing.T) {
	c, cap, done := newTestClient(t)
	defer done()
	if _, err := c.GetMethodSource(ctx(), "com.example.X", "<init>", nil, 0); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(cap.last().RawURL, "%3Cinit%3E") {
		t.Errorf("method name not escaped: %s", cap.last().RawURL)
	}
}
