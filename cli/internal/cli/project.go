package cli

import (
	"os"
	"path/filepath"

	clierrors "github.com/charliek/codelens/cli/internal/errors"
)

// resolveProjectPath turns the --project flag (or cwd when unset) into an
// absolute path and validates that it contains a Gradle project, mirroring
// Python's ProjectService.get_project_path.
func resolveProjectPath(flag string) (string, error) {
	var raw string
	if flag != "" {
		raw = flag
	} else {
		cwd, err := os.Getwd()
		if err != nil {
			return "", err
		}
		raw = cwd
	}
	abs, err := filepath.Abs(raw)
	if err != nil {
		return "", err
	}
	if !isGradleProject(abs) {
		return "", clierrors.New(clierrors.ProjectNotFound,
			"project path %s does not contain build.gradle or build.gradle.kts", abs)
	}
	return abs, nil
}

func isGradleProject(dir string) bool {
	for _, name := range []string{"build.gradle.kts", "build.gradle"} {
		if _, err := os.Stat(filepath.Join(dir, name)); err == nil {
			return true
		}
	}
	return false
}
