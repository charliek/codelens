package cli

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"time"

	"github.com/charliek/codelens/cli/internal/client"
	clierrors "github.com/charliek/codelens/cli/internal/errors"
	"github.com/charliek/codelens/cli/internal/render"
	"github.com/charliek/codelens/cli/internal/server"
	"github.com/charliek/codelens/cli/internal/settings"
	"github.com/charliek/codelens/cli/internal/state"
	"github.com/charliek/codelens/cli/internal/version"
	"github.com/spf13/cobra"
)

// LifecycleService is the slice of *server.Service the lifecycle commands
// need. Defined as an interface so tests can inject fakes.
type LifecycleService interface {
	Find(projectPath string) (*state.ServerState, error)
	Start(ctx context.Context, opts server.StartOptions) (*state.ServerState, error)
	Stop(projectPath string, force bool) (bool, error)
	ListAll() ([]*state.ServerState, error)
}

// lifecycleFactory builds the LifecycleService used at runtime. Tests
// replace this with a fake.
var lifecycleFactory = defaultLifecycleFactory

func defaultLifecycleFactory() (LifecycleService, error) {
	s := settings.Load()
	cacheDir, err := settings.CacheDir()
	if err != nil {
		return nil, err
	}
	repo, err := state.NewRepository(cacheDir, version.Value)
	if err != nil {
		return nil, err
	}
	return server.New(s, repo), nil
}

// clientFactory builds a *client.Client for a running server. Tests replace
// this with a fake.
type adminClient interface {
	Info(ctx context.Context) (json.RawMessage, error)
	Close()
}

var clientFactory = func(host string, port int) adminClient { return client.NewClient(host, port) }

// ============================================================================
// start
// ============================================================================

type startFlags struct {
	port        int
	mode        string
	projectJava string
	timeout     int
	serverJAR   string
}

func newStartCmd() *cobra.Command {
	var f startFlags
	cmd := &cobra.Command{
		Use:     "start",
		Short:   "Start the CodeLens server for a project",
		GroupID: "lifecycle",
		RunE: func(cmd *cobra.Command, _ []string) error {
			return runStart(cmd, f)
		},
	}
	cmd.Flags().IntVar(&f.port, "port", 0, "Port to use (auto-allocated if unset)")
	cmd.Flags().StringVar(&f.mode, "mode", "", "Server mode: auto | gradle | jar")
	cmd.Flags().StringVar(&f.projectJava, "project-java", "", "Java home for target project's Gradle")
	cmd.Flags().IntVar(&f.timeout, "timeout", 180, "Startup timeout in seconds")
	cmd.Flags().StringVar(&f.serverJAR, "server-jar", "", "Override path to codelens-server-all.jar")
	return cmd
}

func runStart(cmd *cobra.Command, f startFlags) error {
	projectPath, err := resolveProjectPath(flagProject)
	if err != nil {
		return err
	}

	svc, err := lifecycleFactory()
	if err != nil {
		return clierrors.New(clierrors.ServerError, "%v", err)
	}

	existing, _ := svc.Find(projectPath)
	if existing != nil && existing.Status == state.StatusReady {
		return emitState(cmd, existing)
	}

	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(f.timeout)*time.Second)
	defer cancel()

	st, err := svc.Start(ctx, server.StartOptions{
		ProjectPath:     projectPath,
		Mode:            state.ServerMode(f.mode),
		Port:            f.port,
		Timeout:         time.Duration(f.timeout) * time.Second,
		ProjectJavaHome: f.projectJava,
		ServerJAR:       f.serverJAR,
	})
	if err != nil {
		if errors.Is(err, context.DeadlineExceeded) {
			return clierrors.New(clierrors.Timeout, "server did not start within %ds", f.timeout)
		}
		return clierrors.New(clierrors.ServerError, "%v", err)
	}
	return emitState(cmd, st)
}

// ============================================================================
// stop
// ============================================================================

func newStopCmd() *cobra.Command {
	var force bool
	cmd := &cobra.Command{
		Use:     "stop",
		Short:   "Stop the CodeLens server for a project",
		GroupID: "lifecycle",
		RunE: func(cmd *cobra.Command, _ []string) error {
			projectPath, err := resolveProjectPath(flagProject)
			if err != nil {
				return err
			}
			svc, err := lifecycleFactory()
			if err != nil {
				return clierrors.New(clierrors.ServerError, "%v", err)
			}
			stopped, err := svc.Stop(projectPath, force)
			if err != nil {
				return clierrors.New(clierrors.ServerError, "%v", err)
			}
			return emitLifecycle(cmd, map[string]any{
				"stopped": stopped,
				"project": projectPath,
			}, func(w io.Writer) error {
				if stopped {
					fmt.Fprintf(w, "Stopped server for %s\n", projectPath)
				} else {
					fmt.Fprintf(w, "No running server for %s\n", projectPath)
				}
				return nil
			})
		},
	}
	cmd.Flags().BoolVar(&force, "force", false, "Force kill if graceful shutdown fails")
	return cmd
}

// ============================================================================
// status
// ============================================================================

func newStatusCmd() *cobra.Command {
	return &cobra.Command{
		Use:     "status",
		Short:   "Show server status for a project",
		GroupID: "lifecycle",
		RunE: func(cmd *cobra.Command, _ []string) error {
			projectPath, err := resolveProjectPath(flagProject)
			if err != nil {
				return err
			}
			svc, err := lifecycleFactory()
			if err != nil {
				return clierrors.New(clierrors.ServerError, "%v", err)
			}
			st, err := svc.Find(projectPath)
			if err != nil {
				return clierrors.New(clierrors.ServerError, "%v", err)
			}

			// Locked wire-contract asymmetry (preserved from the original
			// Python CLI; see git history):
			// when no server is running, emit {"running": false, "project": ...}.
			// When one IS running, emit the merged server-state dict — with
			// NO "running" field at all.
			if st == nil {
				return emitLifecycle(cmd, map[string]any{
					"running": false,
					"project": projectPath,
				}, func(w io.Writer) error {
					return render.KVBlock(w, [][2]string{
						{"Running:", "No"},
						{"Project:", projectPath},
					})
				})
			}
			merged, err := mergeStateWithInfo(st)
			if err != nil {
				return err
			}
			return emitLifecycle(cmd, merged, func(w io.Writer) error {
				return statusTable(w, merged)
			})
		},
	}
}

// statusTable renders the merged status dict as a key/value block, reading a
// fixed set of keys in a stable order (never via map iteration). Keys absent
// from the dict — e.g. live /admin/info fields when it didn't respond — are
// skipped.
func statusTable(w io.Writer, m map[string]any) error {
	fields := []struct{ label, key string }{
		{"Project:", "projectName"},
		{"Path:", "projectPath"},
		{"Status:", "status"},
		{"Mode:", "serverMode"},
		{"Host:", "host"},
		{"Port:", "port"},
		{"PID:", "pid"},
		{"Version:", "version"},
		{"Uptime:", "uptime"},
		{"Idle:", "idleDuration"},
	}
	var rows [][2]string
	for _, f := range fields {
		if v, ok := m[f.key]; ok && v != nil {
			rows = append(rows, [2]string{f.label, fmt.Sprintf("%v", v)})
		}
	}
	return render.KVBlock(w, rows)
}

// mergeStateWithInfo joins the persisted state with /admin/info live data
// (uptime, idleDuration, etc.) just like Python's status command. Failures
// to fetch /admin/info fall back to the bare state dict.
func mergeStateWithInfo(st *state.ServerState) (map[string]any, error) {
	stateBytes, err := json.Marshal(st)
	if err != nil {
		return nil, err
	}
	var merged map[string]any
	if err := json.Unmarshal(stateBytes, &merged); err != nil {
		return nil, err
	}

	cli := clientFactory(st.Host, st.Port)
	defer cli.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	infoRaw, err := cli.Info(ctx)
	if err == nil {
		var info map[string]any
		if err := json.Unmarshal(infoRaw, &info); err == nil {
			for k, v := range info {
				merged[k] = v
			}
		}
	}
	return merged, nil
}

// ============================================================================
// restart
// ============================================================================

func newRestartCmd() *cobra.Command {
	var f startFlags
	cmd := &cobra.Command{
		Use:     "restart",
		Short:   "Restart the CodeLens server for a project",
		GroupID: "lifecycle",
		RunE: func(cmd *cobra.Command, _ []string) error {
			projectPath, err := resolveProjectPath(flagProject)
			if err != nil {
				return err
			}
			svc, err := lifecycleFactory()
			if err != nil {
				return clierrors.New(clierrors.ServerError, "%v", err)
			}
			_, _ = svc.Stop(projectPath, false)

			ctx, cancel := context.WithTimeout(context.Background(), time.Duration(f.timeout)*time.Second)
			defer cancel()
			st, err := svc.Start(ctx, server.StartOptions{
				ProjectPath:     projectPath,
				Mode:            state.ServerMode(f.mode),
				Timeout:         time.Duration(f.timeout) * time.Second,
				ProjectJavaHome: f.projectJava,
				ServerJAR:       f.serverJAR,
			})
			if err != nil {
				if errors.Is(err, context.DeadlineExceeded) {
					return clierrors.New(clierrors.Timeout, "server did not start within %ds", f.timeout)
				}
				return clierrors.New(clierrors.ServerError, "%v", err)
			}
			return emitState(cmd, st)
		},
	}
	cmd.Flags().StringVar(&f.mode, "mode", "", "Server mode: auto | gradle | jar")
	cmd.Flags().StringVar(&f.projectJava, "project-java", "", "Java home for target project's Gradle")
	cmd.Flags().IntVar(&f.timeout, "timeout", 180, "Startup timeout in seconds")
	cmd.Flags().StringVar(&f.serverJAR, "server-jar", "", "Override path to codelens-server-all.jar")
	return cmd
}

// ============================================================================
// refresh
// ============================================================================

func newRefreshCmd() *cobra.Command {
	return &cobra.Command{
		Use:     "refresh",
		Short:   "Refresh the project scan (re-runs the bytecode analysis)",
		GroupID: "lifecycle",
		RunE: func(cmd *cobra.Command, _ []string) error {
			projectPath, err := resolveProjectPath(flagProject)
			if err != nil {
				return err
			}
			svc, err := lifecycleFactory()
			if err != nil {
				return clierrors.New(clierrors.ServerError, "%v", err)
			}
			st, err := svc.Find(projectPath)
			if err != nil {
				return clierrors.New(clierrors.ServerError, "%v", err)
			}
			if st == nil {
				return clierrors.New(clierrors.NotRunning, "no server running for %s", projectPath)
			}

			// POST /api/v1/project/refresh
			cli := clientFactory(st.Host, st.Port)
			defer cli.Close()
			raw, err := postRefresh(cli, st.Host, st.Port)
			if err != nil {
				return clierrors.New(clierrors.ServerError, "%v", err)
			}
			return emitLifecycle(cmd, json.RawMessage(raw), func(w io.Writer) error {
				fmt.Fprintf(w, "Refreshed project scan for %s\n", projectPath)
				return nil
			})
		},
	}
}

// postRefresh is split out so tests can override the HTTP call without
// reaching for real net.
func postRefresh(_ adminClient, host string, port int) ([]byte, error) {
	url := fmt.Sprintf("http://%s:%d/api/v1/project/refresh", host, port)
	req, err := http.NewRequest(http.MethodPost, url, nil)
	if err != nil {
		return nil, err
	}
	hc := &http.Client{Timeout: 60 * time.Second}
	resp, err := hc.Do(req)
	if err != nil {
		return nil, err
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode/100 != 2 {
		return nil, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	return readAll(resp.Body)
}

// ============================================================================
// list
// ============================================================================

func newListCmd() *cobra.Command {
	return &cobra.Command{
		Use:     "list",
		Short:   "List all running CodeLens servers (does not require a project)",
		GroupID: "lifecycle",
		// Skip the global project validation for this command.
		PreRunE: func(_ *cobra.Command, _ []string) error { return nil },
		RunE: func(cmd *cobra.Command, _ []string) error {
			svc, err := lifecycleFactory()
			if err != nil {
				return clierrors.New(clierrors.ServerError, "%v", err)
			}
			servers, err := svc.ListAll()
			if err != nil {
				return clierrors.New(clierrors.ServerError, "%v", err)
			}
			// Python emits {"servers": [...]} as JSON.
			if servers == nil {
				servers = []*state.ServerState{}
			}
			return emitLifecycle(cmd, map[string]any{"servers": servers}, func(w io.Writer) error {
				if len(servers) == 0 {
					fmt.Fprintln(w, "No running servers.")
					return nil
				}
				rows := make([][]string, 0, len(servers))
				for _, s := range servers {
					rows = append(rows, []string{
						s.ProjectName, string(s.ServerMode),
						strconv.Itoa(s.Port), strconv.Itoa(s.PID), string(s.Status),
					})
				}
				return render.Table(w, []string{"Project", "Mode", "Port", "PID", "Status"}, rows)
			})
		},
	}
}

// ============================================================================
// helpers
// ============================================================================

func emitState(cmd *cobra.Command, st *state.ServerState) error {
	return emitLifecycle(cmd, st, func(w io.Writer) error {
		return render.KVBlock(w, [][2]string{
			{"Project:", st.ProjectName},
			{"Path:", st.ProjectPath},
			{"Status:", string(st.Status)},
			{"Mode:", string(st.ServerMode)},
			{"Host:", st.Host},
			{"Port:", strconv.Itoa(st.Port)},
			{"PID:", strconv.Itoa(st.PID)},
		})
	})
}

// readAll: small wrapper so the postRefresh function above stays self-contained.
func readAll(r interface{ Read([]byte) (int, error) }) ([]byte, error) {
	var out []byte
	buf := make([]byte, 4096)
	for {
		n, err := r.Read(buf)
		if n > 0 {
			out = append(out, buf[:n]...)
		}
		if err != nil {
			if err.Error() == "EOF" {
				return out, nil
			}
			return out, err
		}
	}
}
