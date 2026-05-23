package state

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func newTestRepo(t *testing.T) (*Repository, string) {
	t.Helper()
	tmp := t.TempDir()
	repo, err := NewRepository(tmp, "9.9.9-test")
	if err != nil {
		t.Fatalf("NewRepository: %v", err)
	}
	return repo, tmp
}

func TestSaveAndFind(t *testing.T) {
	repo, _ := newTestRepo(t)
	proj := "/tmp/some-project"
	saved, err := repo.Save(proj, 1234, 8080, "127.0.0.1", ServerModeJAR, "30m")
	if err != nil {
		t.Fatal(err)
	}
	if saved.Status != StatusStarting {
		t.Errorf("initial status should be STARTING; got %s", saved.Status)
	}
	if saved.ProjectName != "some-project" {
		t.Errorf("projectName = %s", saved.ProjectName)
	}

	found, err := repo.Find(proj)
	if err != nil {
		t.Fatal(err)
	}
	if found == nil {
		t.Fatal("Find returned nil after Save")
	}
	if found.PID != 1234 {
		t.Errorf("pid = %d", found.PID)
	}
}

func TestFindMissingReturnsNil(t *testing.T) {
	repo, _ := newTestRepo(t)
	got, err := repo.Find("/never/saved")
	if err != nil {
		t.Fatalf("expected nil error, got %v", err)
	}
	if got != nil {
		t.Errorf("expected nil state, got %+v", got)
	}
}

func TestUpdateStatus(t *testing.T) {
	repo, _ := newTestRepo(t)
	proj := "/tmp/proj"
	if _, err := repo.Save(proj, 1, 8080, "127.0.0.1", ServerModeJAR, "30m"); err != nil {
		t.Fatal(err)
	}
	if err := repo.UpdateStatus(proj, StatusReady); err != nil {
		t.Fatal(err)
	}
	got, _ := repo.Find(proj)
	if got.Status != StatusReady {
		t.Errorf("status = %s", got.Status)
	}
}

func TestDelete(t *testing.T) {
	repo, _ := newTestRepo(t)
	proj := "/tmp/proj"
	if _, err := repo.Save(proj, 1, 8080, "127.0.0.1", ServerModeJAR, "30m"); err != nil {
		t.Fatal(err)
	}
	if err := repo.Delete(proj); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(repo.StateFile(proj)); !os.IsNotExist(err) {
		t.Errorf("state file should be gone; stat err = %v", err)
	}
	// Delete on missing file should not error.
	if err := repo.Delete(proj); err != nil {
		t.Errorf("delete missing should be no-op, got %v", err)
	}
}

func TestStateFileNaming(t *testing.T) {
	repo, _ := newTestRepo(t)
	got := repo.StateFile("/tmp/codelens-port-fixture")
	want := filepath.Join(repo.cacheDir, "servers", "a7dcdd48e71b.json")
	if got != want {
		t.Errorf("StateFile = %s, want %s", got, want)
	}
}

func TestListAllSkipsAndCleansStale(t *testing.T) {
	repo, tmp := newTestRepo(t)
	// One running entry — use this process's own pid so signal 0 succeeds.
	if _, err := repo.Save("/proj/alive", os.Getpid(), 8080, "127.0.0.1", ServerModeJAR, "30m"); err != nil {
		t.Fatal(err)
	}
	// One stale entry pointing at a pid that's definitely not running.
	stalePID := 1 // init is technically alive, so use a very high pid that's almost certainly free.
	for try := 999999; try > 1000; try-- {
		if !IsProcessRunning(try) {
			stalePID = try
			break
		}
	}
	if _, err := repo.Save("/proj/dead", stalePID, 8080, "127.0.0.1", ServerModeJAR, "30m"); err != nil {
		t.Fatal(err)
	}
	servers, err := repo.ListAll()
	if err != nil {
		t.Fatal(err)
	}
	if len(servers) != 1 {
		t.Errorf("expected 1 alive server; got %d", len(servers))
	}
	if servers[0].PID != os.Getpid() {
		t.Errorf("wrong server kept")
	}
	// Stale file should be gone.
	staleFile := filepath.Join(tmp, "servers", ProjectHash("/proj/dead")+".json")
	if _, err := os.Stat(staleFile); !os.IsNotExist(err) {
		t.Errorf("stale state file should be cleaned up")
	}
}

// Golden fixture: the state file format must be byte-identical to what
// Python wrote during Phase 0. The fixture at testdata/state-sample.json
// uses the placeholder __PROJECT_PATH__ so the file has no developer-
// machine paths; we substitute a temp-dir path at test time.
func TestGoldenStateFileRoundTrip(t *testing.T) {
	raw, err := os.ReadFile("../../testdata/state-sample.json")
	if err != nil {
		t.Fatal(err)
	}
	substituted := strings.ReplaceAll(string(raw), "__PROJECT_PATH__", t.TempDir())

	var state ServerState
	if err := json.Unmarshal([]byte(substituted), &state); err != nil {
		t.Fatalf("unmarshal golden: %v", err)
	}
	got, err := json.MarshalIndent(state, "", "  ")
	if err != nil {
		t.Fatal(err)
	}
	// Cross-check field-by-field via map.
	var want, gotMap map[string]any
	_ = json.Unmarshal([]byte(substituted), &want)
	_ = json.Unmarshal(got, &gotMap)
	for k, v := range want {
		if gotMap[k] != v {
			t.Errorf("field %q mismatch: got %v, want %v", k, gotMap[k], v)
		}
	}
}

// PythonTime marshals to "2026-05-23T04:18:39.650582Z" — T separator,
// Z suffix, exactly six microsecond digits. Verified against Phase 0 baseline.
func TestPythonTimeFormat(t *testing.T) {
	pt := PythonTime{time.Date(2026, 5, 23, 4, 18, 39, 650582000, time.UTC)}
	out, err := json.Marshal(pt)
	if err != nil {
		t.Fatal(err)
	}
	want := `"2026-05-23T04:18:39.650582Z"`
	if string(out) != want {
		t.Errorf("PythonTime json = %s, want %s", out, want)
	}
	// Round-trip.
	var back PythonTime
	if err := json.Unmarshal(out, &back); err != nil {
		t.Fatal(err)
	}
	if !back.Equal(pt.Time) {
		t.Errorf("round-trip lost precision: %v vs %v", back.Time, pt.Time)
	}
}

func TestIsProcessRunning(t *testing.T) {
	if !IsProcessRunning(os.Getpid()) {
		t.Errorf("current pid should be running")
	}
	if IsProcessRunning(0) {
		t.Errorf("pid 0 should not be reported running")
	}
}
