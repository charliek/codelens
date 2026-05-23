package state

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"time"
)

// ServerMode mirrors cli/src/codelens_cli/models.py:ServerMode.
type ServerMode string

const (
	ServerModeAuto   ServerMode = "auto"
	ServerModeGradle ServerMode = "gradle"
	ServerModeJAR    ServerMode = "jar"
)

// ProjectStatus mirrors cli/src/codelens_cli/models.py:ProjectStatus.
type ProjectStatus string

const (
	StatusStarting ProjectStatus = "STARTING"
	StatusLoading  ProjectStatus = "LOADING"
	StatusReady    ProjectStatus = "READY"
	StatusError    ProjectStatus = "ERROR"
)

// PythonTime wraps time.Time with a JSON marshaler that produces Python /
// Pydantic-compatible bytes: "2026-05-23T04:18:39.650582Z" — T separator,
// Z suffix, exactly six microsecond digits. Verified against the actual
// state file produced by the Phase 0 baseline run.
type PythonTime struct {
	time.Time
}

// pythonTimeLayout is the format string used to write timestamps.
const pythonTimeLayout = "2006-01-02T15:04:05.000000Z"

// MarshalJSON emits the Python-compatible UTC representation.
func (t PythonTime) MarshalJSON() ([]byte, error) {
	utc := t.UTC()
	return []byte(`"` + utc.Format(pythonTimeLayout) + `"`), nil
}

// UnmarshalJSON accepts the on-disk representation we emit as well as the
// looser variants the Python tests sometimes produce in fixtures.
func (t *PythonTime) UnmarshalJSON(data []byte) error {
	if len(data) < 2 || data[0] != '"' || data[len(data)-1] != '"' {
		return fmt.Errorf("invalid datetime: %s", data)
	}
	s := string(data[1 : len(data)-1])
	// Try the strict on-disk format first.
	if parsed, err := time.Parse(pythonTimeLayout, s); err == nil {
		t.Time = parsed
		return nil
	}
	// Fall back to RFC3339Nano for forward compatibility.
	for _, layout := range []string{time.RFC3339Nano, time.RFC3339, "2006-01-02 15:04:05.000000-07:00"} {
		if parsed, err := time.Parse(layout, s); err == nil {
			t.Time = parsed
			return nil
		}
	}
	return fmt.Errorf("unrecognized datetime: %s", s)
}

// ServerState mirrors cli/src/codelens_cli/models.py:ServerState. Field order
// matches the Python output so on-disk JSON is byte-identical when the same
// inputs are supplied.
type ServerState struct {
	PID            int           `json:"pid"`
	Port           int           `json:"port"`
	Host           string        `json:"host"`
	ProjectPath    string        `json:"projectPath"`
	ProjectName    string        `json:"projectName"`
	StartedAt      PythonTime    `json:"startedAt"`
	LastActivityAt PythonTime    `json:"lastActivityAt"`
	IdleTimeout    string        `json:"idleTimeout"` // string, e.g. "30m"
	Status         ProjectStatus `json:"status"`
	ServerMode     ServerMode    `json:"serverMode"`
	Version        string        `json:"version"`
}

// Repository persists ServerState to ~/.cache/codelens/servers/<hash>.json.
type Repository struct {
	cacheDir   string
	cliVersion string
}

// NewRepository creates the cache directories if they don't exist.
//
// cacheDir is typically ~/.cache/codelens — hardcoded across platforms to
// match Python's settings.py:69-73 (and to avoid Go's os.UserCacheDir()
// which uses ~/Library/Caches on macOS).
func NewRepository(cacheDir, cliVersion string) (*Repository, error) {
	if err := os.MkdirAll(filepath.Join(cacheDir, "servers"), 0o755); err != nil {
		return nil, err
	}
	if err := os.MkdirAll(filepath.Join(cacheDir, "logs"), 0o755); err != nil {
		return nil, err
	}
	return &Repository{cacheDir: cacheDir, cliVersion: cliVersion}, nil
}

// StateFile returns the absolute path to a project's state file.
func (r *Repository) StateFile(projectPath string) string {
	return filepath.Join(r.cacheDir, "servers", ProjectHash(projectPath)+".json")
}

// LogFile returns the absolute path to a project's server log file.
func (r *Repository) LogFile(projectPath string) string {
	return filepath.Join(r.cacheDir, "logs", ProjectHash(projectPath)+".log")
}

// Save writes the initial state for a newly-spawned server. status starts
// at STARTING — call UpdateStatus once ready is observed.
func (r *Repository) Save(projectPath string, pid, port int, host string, mode ServerMode, idleTimeout string) (*ServerState, error) {
	now := PythonTime{time.Now().UTC()}
	state := &ServerState{
		PID:            pid,
		Port:           port,
		Host:           host,
		ProjectPath:    projectPath,
		ProjectName:    filepath.Base(projectPath),
		StartedAt:      now,
		LastActivityAt: now,
		IdleTimeout:    idleTimeout,
		Status:         StatusStarting,
		ServerMode:     mode,
		Version:        r.cliVersion,
	}
	if err := r.write(projectPath, state); err != nil {
		return nil, err
	}
	return state, nil
}

// Find reads the state file. Returns nil, nil if the file is missing or
// unparseable (matching Python's behavior).
func (r *Repository) Find(projectPath string) (*ServerState, error) {
	data, err := os.ReadFile(r.StateFile(projectPath))
	if err != nil {
		if os.IsNotExist(err) {
			return nil, nil
		}
		return nil, err
	}
	var state ServerState
	if err := json.Unmarshal(data, &state); err != nil {
		// Match Python: stale/corrupt state file is the same as no file.
		return nil, nil
	}
	return &state, nil
}

// UpdateStatus refreshes status and lastActivityAt on an existing state file.
func (r *Repository) UpdateStatus(projectPath string, status ProjectStatus) error {
	state, err := r.Find(projectPath)
	if err != nil || state == nil {
		return err
	}
	state.Status = status
	state.LastActivityAt = PythonTime{time.Now().UTC()}
	return r.write(projectPath, state)
}

// Delete removes the state file. Missing file is not an error.
func (r *Repository) Delete(projectPath string) error {
	err := os.Remove(r.StateFile(projectPath))
	if err != nil && !os.IsNotExist(err) {
		return err
	}
	return nil
}

// IsProcessRunning checks via signal 0 (mirrors Python os.kill(pid, 0)).
func IsProcessRunning(pid int) bool {
	if pid <= 0 {
		return false
	}
	proc, err := os.FindProcess(pid)
	if err != nil {
		return false
	}
	return proc.Signal(syscall.Signal(0)) == nil
}

// ListAll returns every running server. Stale state files (where the PID is
// no longer alive) are cleaned up as a side effect, matching Python.
func (r *Repository) ListAll() ([]*ServerState, error) {
	dir := filepath.Join(r.cacheDir, "servers")
	entries, err := os.ReadDir(dir)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, nil
		}
		return nil, err
	}
	var out []*ServerState
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".json") {
			continue
		}
		path := filepath.Join(dir, e.Name())
		data, err := os.ReadFile(path)
		if err != nil {
			continue
		}
		var s ServerState
		if err := json.Unmarshal(data, &s); err != nil {
			_ = os.Remove(path)
			continue
		}
		if !IsProcessRunning(s.PID) {
			_ = os.Remove(path)
			continue
		}
		out = append(out, &s)
	}
	return out, nil
}

func (r *Repository) write(projectPath string, state *ServerState) error {
	data, err := json.MarshalIndent(state, "", "  ")
	if err != nil {
		return err
	}
	// Python's `model_dump_json(..., indent=2)` ends without a trailing
	// newline. Match.
	return os.WriteFile(r.StateFile(projectPath), data, 0o644)
}
