package cli

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	clierrors "github.com/charliek/codelens/cli/internal/errors"
)

// TestHandlersList_MissingInjectFiltersClientSide locks the documented
// Python --missing-inject behavior: drop entries where hasInjectAnnotation
// is true (or absent), keep the others. Filtering happens client-side; the
// HTTP request is unchanged.
func TestHandlersList_MissingInjectFiltersClientSide(t *testing.T) {
	proj := gradleProjectDir(t)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte(`{
			"handlers": [
				{"fqn":"com.x.A","hasInjectAnnotation":true,"complexityTier":"LOW"},
				{"fqn":"com.x.B","hasInjectAnnotation":false,"complexityTier":"MEDIUM"},
				{"fqn":"com.x.C","complexityTier":"HIGH"}
			],
			"totalCount": 3
		}`))
	}))
	defer srv.Close()
	withMockClient(t, srv)
	installFake(t, &fakeLifecycle{findResult: mustReady(proj, 8080)})

	res := runCLI(t, "handlers", "list", "--missing-inject", "--project", proj)
	if res.exitCode != clierrors.Success {
		t.Fatalf("exit=%d, stderr=%s", res.exitCode, res.stderr)
	}

	var out map[string]any
	if err := json.Unmarshal([]byte(res.stdout), &out); err != nil {
		t.Fatalf("not JSON: %s", res.stdout)
	}
	handlers, ok := out["handlers"].([]any)
	if !ok {
		t.Fatalf("missing handlers array: %v", out)
	}
	if len(handlers) != 2 {
		t.Fatalf("expected 2 surviving handlers (B + C); got %d: %v", len(handlers), handlers)
	}
	// First two should be B and C in that order.
	got := []string{}
	for _, h := range handlers {
		hm := h.(map[string]any)
		got = append(got, hm["fqn"].(string))
	}
	if got[0] != "com.x.B" || got[1] != "com.x.C" {
		t.Errorf("wrong handlers after filter: %v", got)
	}
}

// Without the flag, the response passes through unchanged.
func TestHandlersList_NoFlagIsPassthrough(t *testing.T) {
	proj := gradleProjectDir(t)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte(`{"handlers":[{"fqn":"com.x.A","hasInjectAnnotation":true}],"totalCount":1}`))
	}))
	defer srv.Close()
	withMockClient(t, srv)
	installFake(t, &fakeLifecycle{findResult: mustReady(proj, 8080)})

	res := runCLI(t, "handlers", "list", "--project", proj)
	if res.exitCode != clierrors.Success {
		t.Fatalf("exit=%d", res.exitCode)
	}
	var out map[string]any
	_ = json.Unmarshal([]byte(res.stdout), &out)
	hs, _ := out["handlers"].([]any)
	if len(hs) != 1 {
		t.Errorf("expected pass-through (1 handler); got %d", len(hs))
	}
}
