// Package client is the HTTP client for the CodeLens server. It is a 1:1
// port of cli/src/codelens_cli/client.py; the wire contract (path encoding,
// query param shapes, POST body fields) is locked by client_test.go and
// MUST match the Python CLI byte for byte so a running server can serve
// either CLI interchangeably.
package client

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// Client communicates with a running CodeLens server.
type Client struct {
	BaseURL string
	HTTP    *http.Client
}

// NewClient constructs a Client targeting http://host:port with a sensible
// default timeout.
func NewClient(host string, port int) *Client {
	return &Client{
		BaseURL: fmt.Sprintf("http://%s:%d", host, port),
		HTTP: &http.Client{
			Timeout: 30 * time.Second,
		},
	}
}

// Close releases idle keep-alive connections. Idempotent.
func (c *Client) Close() {
	if c.HTTP != nil {
		c.HTTP.CloseIdleConnections()
	}
}

// HTTPError is returned when the server responds with a non-2xx status.
type HTTPError struct {
	Status int
	URL    string
	Body   string
}

func (e *HTTPError) Error() string {
	return fmt.Sprintf("HTTP %d for %s: %s", e.Status, e.URL, e.Body)
}

// params is an ordered list of (key,value) pairs. Order isn't part of the
// contract, but using a map would clash with Python's behavior of including
// only explicitly-set params (an empty map means an empty query string and
// would still produce `?` in some libs).
type params struct {
	items []param
}

type param struct {
	key, value string
}

func (p *params) add(key, value string) {
	p.items = append(p.items, param{key, value})
}

func (p *params) encode() string {
	if len(p.items) == 0 {
		return ""
	}
	values := url.Values{}
	for _, it := range p.items {
		values.Add(it.key, it.value)
	}
	return values.Encode()
}

// urlFor builds the full URL with a path and (optional) query string. The
// path is appended verbatim — caller is responsible for encoding any FQN
// segments via pythonQuote.
func (c *Client) urlFor(path string, p *params) string {
	if p == nil || len(p.items) == 0 {
		return c.BaseURL + path
	}
	return c.BaseURL + path + "?" + p.encode()
}

func (c *Client) doGet(ctx context.Context, path string, p *params) (json.RawMessage, error) {
	full := c.urlFor(path, p)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, full, nil)
	if err != nil {
		return nil, err
	}
	return c.do(req)
}

func (c *Client) doPostJSON(ctx context.Context, path string, body any) (json.RawMessage, error) {
	full := c.urlFor(path, nil)
	var reader io.Reader
	if body != nil {
		buf, err := json.Marshal(body)
		if err != nil {
			return nil, err
		}
		reader = bytes.NewReader(buf)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, full, reader)
	if err != nil {
		return nil, err
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	return c.do(req)
}

// doRaw returns the raw body bytes (for DOT output). It still raises HTTPError
// on non-2xx.
func (c *Client) doRaw(ctx context.Context, path string, p *params) ([]byte, error) {
	full := c.urlFor(path, p)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, full, nil)
	if err != nil {
		return nil, err
	}
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return nil, err
	}
	defer func() { _ = resp.Body.Close() }()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode/100 != 2 {
		return nil, &HTTPError{Status: resp.StatusCode, URL: full, Body: string(body)}
	}
	return body, nil
}

func (c *Client) do(req *http.Request) (json.RawMessage, error) {
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return nil, err
	}
	defer func() { _ = resp.Body.Close() }()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode/100 != 2 {
		return nil, &HTTPError{Status: resp.StatusCode, URL: req.URL.String(), Body: string(body)}
	}
	if len(body) == 0 {
		return nil, nil
	}
	return json.RawMessage(body), nil
}

// =============================================================================
// Admin
// =============================================================================

func (c *Client) Health(ctx context.Context) (json.RawMessage, error) {
	return c.doGet(ctx, "/admin/health", nil)
}

func (c *Client) Ready(ctx context.Context) (json.RawMessage, error) {
	return c.doGet(ctx, "/admin/ready", nil)
}

func (c *Client) Info(ctx context.Context) (json.RawMessage, error) {
	return c.doGet(ctx, "/admin/info", nil)
}

func (c *Client) Project(ctx context.Context) (json.RawMessage, error) {
	return c.doGet(ctx, "/api/v1/project", nil)
}

func (c *Client) Refresh(ctx context.Context) (json.RawMessage, error) {
	return c.doPostJSON(ctx, "/api/v1/project/refresh", nil)
}

// TouchActivity is best-effort — swallows all errors (mirrors client.py:82-87).
func (c *Client) TouchActivity(ctx context.Context) {
	_, _ = c.doPostJSON(ctx, "/admin/activity", nil)
}

func (c *Client) Stats(ctx context.Context) (json.RawMessage, error) {
	return c.doGet(ctx, "/api/v1/stats", nil)
}

func (c *Client) Shutdown(ctx context.Context) (json.RawMessage, error) {
	return c.doPostJSON(ctx, "/admin/shutdown", nil)
}

// =============================================================================
// Classes
// =============================================================================

// ListClassesFilter mirrors the filter args of client.py:list_classes.
type ListClassesFilter struct {
	Package          string
	Name             string
	Annotation       string
	Extends          string
	Implements       string
	InterfacesOnly   bool
	IncludeLibraries bool
	Page             int
	Size             int
}

func (c *Client) ListClasses(ctx context.Context, f ListClassesFilter) (json.RawMessage, error) {
	p := &params{}
	size := f.Size
	if size == 0 {
		size = 50
	}
	p.add("page", strconv.Itoa(f.Page))
	p.add("size", strconv.Itoa(size))
	if f.Package != "" {
		p.add("package", f.Package)
	}
	if f.Name != "" {
		p.add("name", f.Name)
	}
	if f.Annotation != "" {
		p.add("annotation", f.Annotation)
	}
	if f.Extends != "" {
		p.add("extends", f.Extends)
	}
	if f.Implements != "" {
		p.add("implements", f.Implements)
	}
	if f.InterfacesOnly {
		p.add("interfaces", "true")
	}
	if f.IncludeLibraries {
		p.add("includeLibraries", "true")
	}
	return c.doGet(ctx, "/api/v1/classes", p)
}

func (c *Client) GetClass(ctx context.Context, fqn string) (json.RawMessage, error) {
	return c.doGet(ctx, "/api/v1/classes/"+pythonQuote(fqn), nil)
}

func (c *Client) GetImplementations(ctx context.Context, fqn string, includeLibraries bool) (json.RawMessage, error) {
	p := &params{}
	if includeLibraries {
		p.add("includeLibraries", "true")
	}
	return c.doGet(ctx, "/api/v1/implementations/"+pythonQuote(fqn), p)
}

func (c *Client) GetHierarchy(ctx context.Context, fqn string) (json.RawMessage, error) {
	return c.doGet(ctx, "/api/v1/hierarchy/"+pythonQuote(fqn), nil)
}

func (c *Client) GetDependencies(ctx context.Context, fqn string, includeLibraries bool) (json.RawMessage, error) {
	p := &params{}
	if includeLibraries {
		p.add("includeLibraries", "true")
	}
	return c.doGet(ctx, "/api/v1/dependencies/"+pythonQuote(fqn), p)
}

func (c *Client) GetAnnotationUsages(ctx context.Context, fqn string, includeLibraries bool) (json.RawMessage, error) {
	p := &params{}
	if includeLibraries {
		p.add("includeLibraries", "true")
	}
	return c.doGet(ctx, "/api/v1/annotations/usages/"+pythonQuote(fqn), p)
}

// =============================================================================
// Methods
// =============================================================================

// SearchMethodsFilter mirrors client.py:search_methods.
type SearchMethodsFilter struct {
	Name             string
	ReturnType       string
	Annotation       string
	InClass          string
	InPackage        string
	IncludeLibraries bool
	Page             int
	Size             int
}

func (c *Client) SearchMethods(ctx context.Context, f SearchMethodsFilter) (json.RawMessage, error) {
	p := &params{}
	size := f.Size
	if size == 0 {
		size = 50
	}
	p.add("page", strconv.Itoa(f.Page))
	p.add("size", strconv.Itoa(size))
	if f.Name != "" {
		p.add("name", f.Name)
	}
	if f.ReturnType != "" {
		p.add("returnType", f.ReturnType)
	}
	if f.Annotation != "" {
		p.add("annotation", f.Annotation)
	}
	if f.InClass != "" {
		p.add("inClass", f.InClass)
	}
	if f.InPackage != "" {
		p.add("inPackage", f.InPackage)
	}
	if f.IncludeLibraries {
		p.add("includeLibraries", "true")
	}
	return c.doGet(ctx, "/api/v1/methods", p)
}

// =============================================================================
// Calls (forward call-site extraction)
// =============================================================================

// GetCalls returns the invocations a class's method bodies make. When method
// is non-empty, only that method is scanned and descriptor (when set)
// disambiguates overloads by exact JVM descriptor. The FQN is a single
// percent-encoded path segment (matching the sibling class endpoints);
// method/descriptor are query parameters.
func (c *Client) GetCalls(ctx context.Context, fqn, method, descriptor string) (json.RawMessage, error) {
	p := &params{}
	if method != "" {
		p.add("method", method)
		// descriptor only disambiguates a named method; the whole-class view
		// ignores it, so don't send it without a method.
		if descriptor != "" {
			p.add("descriptor", descriptor)
		}
	}
	return c.doGet(ctx, "/api/v1/calls/"+pythonQuote(fqn), p)
}

// =============================================================================
// Dependency graph (general, project-wide)
// =============================================================================

// GetGraph returns the project-wide dependency graph. For format == "dot" it
// returns raw bytes (written verbatim); otherwise json.RawMessage.
func (c *Client) GetGraph(ctx context.Context, format string) (any, error) {
	if format == "dot" {
		p := &params{}
		p.add("format", "dot")
		return c.doRaw(ctx, "/api/v1/graph", p)
	}
	return c.doGet(ctx, "/api/v1/graph", nil)
}

// GetFoundation returns the most depended-on project classes. minDependents <= 0
// omits the parameter (the server default applies).
func (c *Client) GetFoundation(ctx context.Context, minDependents int) (json.RawMessage, error) {
	p := &params{}
	if minDependents > 0 {
		p.add("minDependents", strconv.Itoa(minDependents))
	}
	return c.doGet(ctx, "/api/v1/graph/foundation", p)
}

// =============================================================================
// Xref (inverse type cross-reference)
// =============================================================================

// XrefFilter mirrors the query parameters of the xref endpoint.
type XrefFilter struct {
	IncludeLibraries  bool
	Kind              string
	ScopeImplementing string
	Page              int
	Size              int
}

// GetXref finds everything that references typeFqn. The type FQN is a single
// percent-encoded path segment; the rest are query parameters.
func (c *Client) GetXref(ctx context.Context, typeFqn string, f XrefFilter) (json.RawMessage, error) {
	p := &params{}
	size := f.Size
	if size == 0 {
		size = 50
	}
	p.add("page", strconv.Itoa(f.Page))
	p.add("size", strconv.Itoa(size))
	if f.IncludeLibraries {
		p.add("includeLibraries", "true")
	}
	if f.Kind != "" {
		p.add("kind", f.Kind)
	}
	if f.ScopeImplementing != "" {
		p.add("scopeImplementing", f.ScopeImplementing)
	}
	return c.doGet(ctx, "/api/v1/xref/"+pythonQuote(typeFqn), p)
}

// =============================================================================
// Ktlint (typed responses)
// =============================================================================

func (c *Client) LintFile(ctx context.Context, filePath string) (*LintFileResponse, error) {
	raw, err := c.doPostJSON(ctx, "/api/v1/ktlint/lint/file", map[string]any{"filePath": filePath})
	if err != nil {
		return nil, err
	}
	var out LintFileResponse
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) LintProject(ctx context.Context, pattern string, includeTests bool) (*LintProjectResponse, error) {
	body := map[string]any{"includeTests": includeTests}
	if pattern != "" {
		body["pattern"] = pattern
	}
	raw, err := c.doPostJSON(ctx, "/api/v1/ktlint/lint/project", body)
	if err != nil {
		return nil, err
	}
	var out LintProjectResponse
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) FormatFile(ctx context.Context, filePath string, writeToFile bool) (*FormatFileResponse, error) {
	body := map[string]any{"filePath": filePath, "writeToFile": writeToFile}
	raw, err := c.doPostJSON(ctx, "/api/v1/ktlint/format/file", body)
	if err != nil {
		return nil, err
	}
	var out FormatFileResponse
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) FormatProject(ctx context.Context, pattern string, includeTests, dryRun bool) (*FormatProjectResponse, error) {
	body := map[string]any{"includeTests": includeTests, "dryRun": dryRun}
	if pattern != "" {
		body["pattern"] = pattern
	}
	raw, err := c.doPostJSON(ctx, "/api/v1/ktlint/format/project", body)
	if err != nil {
		return nil, err
	}
	var out FormatProjectResponse
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// =============================================================================
// Source code retrieval
// =============================================================================

func (c *Client) GetSource(ctx context.Context, fqn string) (json.RawMessage, error) {
	return c.doGet(ctx, "/api/v1/source/"+pythonQuote(fqn), nil)
}

func (c *Client) GetMethodSource(ctx context.Context, fqn, methodName string, paramTypes []string, contextLines int) (json.RawMessage, error) {
	p := &params{}
	if len(paramTypes) > 0 {
		p.add("paramTypes", strings.Join(paramTypes, ","))
	}
	if contextLines > 0 {
		p.add("context", strconv.Itoa(contextLines))
	}
	path := "/api/v1/source/" + pythonQuote(fqn) + "/method/" + pythonQuote(methodName)
	return c.doGet(ctx, path, p)
}
