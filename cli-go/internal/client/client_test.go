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
		Implements:       "ratpack.handling.Handler",
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
		"implements":       "ratpack.handling.Handler",
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
	if _, err := c.GetImplementations(ctx(), "ratpack.handling.Handler", false); err != nil {
		t.Fatal(err)
	}
	if cap.last().RawURL != "/api/v1/implementations/ratpack.handling.Handler" {
		t.Errorf("false case should have no query string; got %s", cap.last().RawURL)
	}
	if _, err := c.GetImplementations(ctx(), "ratpack.handling.Handler", true); err != nil {
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
	if _, err := c.GetAnnotationUsages(ctx(), "javax.inject.Singleton", false); err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(cap.last().Path, "/api/v1/annotations/usages/") {
		t.Errorf("path = %s", cap.last().Path)
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
		ReturnType:       "ratpack.exec.Promise",
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
		"returnType":       "ratpack.exec.Promise",
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
		return 200, []byte(`{"projectPath":"/tmp","fileResults":[],"filesScanned":0,"filesWithChanges":0,"durationMs":1}`)
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

// ====================== ratpack ======================

func TestRatpack_ListHandlers_NoFiltersHasEmptyQueryString(t *testing.T) {
	c, cap, done := newTestClient(t)
	defer done()
	if _, err := c.ListHandlers(ctx(), "", "", false); err != nil {
		t.Fatal(err)
	}
	if cap.last().RawURL != "/api/v1/ratpack/handlers" {
		t.Errorf("expected empty query string; got %s", cap.last().RawURL)
	}
}

func TestRatpack_ListHandlers_AllFiltersAndCamelCase(t *testing.T) {
	c, cap, done := newTestClient(t)
	defer done()
	if _, err := c.ListHandlers(ctx(), "GET", "HIGH", true); err != nil {
		t.Fatal(err)
	}
	q := cap.last().Query
	if q.Get("type") != "GET" || q.Get("tier") != "HIGH" || q.Get("includeLibraries") != "true" {
		t.Errorf("query = %v", q)
	}
}

func TestRatpack_ListIntegrations_FilterKeysCamelCase(t *testing.T) {
	c, cap, done := newTestClient(t)
	defer done()
	if _, err := c.ListIntegrations(ctx(), "HTTP_CLIENT", "RATPACK_HTTP_CLIENT", false); err != nil {
		t.Fatal(err)
	}
	q := cap.last().Query
	if q.Get("type") != "HTTP_CLIENT" || q.Get("subType") != "RATPACK_HTTP_CLIENT" {
		t.Errorf("query = %v", q)
	}
}

func TestRatpack_Complexity_PerClass(t *testing.T) {
	c, cap, done := newTestClient(t)
	defer done()
	if _, err := c.GetComplexity(ctx(), "com.example.UserHandler"); err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(cap.last().Path, "/api/v1/ratpack/complexity/") {
		t.Errorf("path = %s", cap.last().Path)
	}
}

func TestRatpack_MigrationOrder(t *testing.T) {
	c, cap, done := newTestClient(t)
	defer done()
	if _, err := c.GetMigrationOrder(ctx()); err != nil {
		t.Fatal(err)
	}
	if cap.last().Path != "/api/v1/ratpack/migration-order" {
		t.Errorf("path = %s", cap.last().Path)
	}
}

// Gap fill: tri-state Promise filters. test_client.py never exercised
// these, but they have to send literal "true"/"false" (not omitted) when
// explicitly set false.
func TestRatpack_SearchPromises_TriStateBooleans(t *testing.T) {
	tBool := true
	fBool := false

	t.Run("omitted", func(t *testing.T) {
		c, cap, done := newTestClient(t)
		defer done()
		if _, err := c.SearchPromises(ctx(), SearchPromisesFilter{}); err != nil {
			t.Fatal(err)
		}
		if cap.last().RawURL != "/api/v1/ratpack/promises/search" {
			t.Errorf("expected empty query string; got %s", cap.last().RawURL)
		}
	})

	t.Run("explicit true", func(t *testing.T) {
		c, cap, done := newTestClient(t)
		defer done()
		if _, err := c.SearchPromises(ctx(), SearchPromisesFilter{
			UsesBlocking: &tBool,
			UsesAsync:    &tBool,
			UsesFork:     &tBool,
		}); err != nil {
			t.Fatal(err)
		}
		q := cap.last().Query
		for _, k := range []string{"usesBlocking", "usesAsync", "usesFork"} {
			if q.Get(k) != "true" {
				t.Errorf("query[%q] = %q, want \"true\"", k, q.Get(k))
			}
		}
	})

	t.Run("explicit false", func(t *testing.T) {
		c, cap, done := newTestClient(t)
		defer done()
		if _, err := c.SearchPromises(ctx(), SearchPromisesFilter{
			UsesBlocking: &fBool,
			UsesAsync:    &fBool,
			UsesFork:     &fBool,
		}); err != nil {
			t.Fatal(err)
		}
		q := cap.last().Query
		for _, k := range []string{"usesBlocking", "usesAsync", "usesFork"} {
			if q.Get(k) != "false" {
				t.Errorf("query[%q] = %q, want \"false\"", k, q.Get(k))
			}
		}
	})

	t.Run("min operations only when > 0", func(t *testing.T) {
		c, cap, done := newTestClient(t)
		defer done()
		if _, err := c.SearchPromises(ctx(), SearchPromisesFilter{MinOperations: 5}); err != nil {
			t.Fatal(err)
		}
		if cap.last().Query.Get("minOperations") != "5" {
			t.Errorf("minOperations not sent")
		}
		// Zero is omitted (matches Python `min_operations > 0` guard).
		if _, err := c.SearchPromises(ctx(), SearchPromisesFilter{MinOperations: 0}); err != nil {
			t.Fatal(err)
		}
		if cap.last().Query.Has("minOperations") {
			t.Errorf("minOperations=0 must be omitted")
		}
	})
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
		[]string{"ratpack.handling.Context", "java.lang.String"},
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
	if got := r.Query.Get("paramTypes"); got != "ratpack.handling.Context,java.lang.String" {
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

// Gap fill: module / binding paths.
func TestRatpack_ModuleAndBindingPaths(t *testing.T) {
	c, cap, done := newTestClient(t)
	defer done()
	if _, err := c.GetModule(ctx(), "com.example.AppModule"); err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(cap.last().Path, "/api/v1/ratpack/modules/") {
		t.Errorf("path = %s", cap.last().Path)
	}
	if _, err := c.GetBindings(ctx(), "com.example.Service"); err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(cap.last().Path, "/api/v1/ratpack/bindings/") {
		t.Errorf("path = %s", cap.last().Path)
	}
}

// Gap fill: both DOT endpoints return raw bytes, not JSON.
func TestDeps_DOTReturnsRawBody(t *testing.T) {
	c, cap, done := newTestClientWith(t, func(_ *http.Request) (int, []byte) {
		return 200, []byte("digraph G { A -> B }\n")
	})
	defer done()
	body, err := c.GetDependencyAnalysis(ctx(), "dot")
	if err != nil {
		t.Fatal(err)
	}
	if string(body.([]byte)) != "digraph G { A -> B }\n" {
		t.Errorf("dot body unexpectedly transformed: %q", body)
	}
	if cap.last().Query.Get("format") != "dot" {
		t.Errorf("format=dot not on query")
	}

	body, err = c.GetDependencyGraph(ctx(), "dot")
	if err != nil {
		t.Fatal(err)
	}
	if string(body.([]byte)) != "digraph G { A -> B }\n" {
		t.Errorf("graph dot body wrong: %q", body)
	}
}

// Gap fill: empty query string is empty.
func TestEmptyQueryStringNoQuestionMark(t *testing.T) {
	c, cap, done := newTestClient(t)
	defer done()
	if _, err := c.Project(ctx()); err != nil {
		t.Fatal(err)
	}
	if cap.last().RawURL != "/api/v1/project" {
		t.Errorf("expected raw URL without ?; got %s", cap.last().RawURL)
	}
}

// touch_activity is best-effort — server errors must not propagate.
func TestTouchActivity_SwallowsErrors(t *testing.T) {
	c, _, done := newTestClientWith(t, func(_ *http.Request) (int, []byte) {
		return 500, []byte(`{"error":"boom"}`)
	})
	defer done()
	// Must not panic, must not error.
	c.TouchActivity(ctx())
}
