package settings

import (
	"errors"
	"os"
	"path/filepath"
)

// ErrRepoNotFound is returned when the CodeLens repo root can't be located.
var ErrRepoNotFound = errors.New("could not find CodeLens repository (set CODELENS_REPO_PATH)")

// FindRepoPath locates the CodeLens repository root. Mirrors Python
// find_repo_path (settings.py:81-104): explicit env var first, otherwise
// walk up to ten directories looking for gradlew + settings.gradle.kts.
func FindRepoPath(s *Settings) (string, error) {
	if s != nil && s.RepoPath != "" {
		return s.RepoPath, nil
	}
	exe, err := os.Executable()
	if err == nil {
		if root := walkUp(filepath.Dir(exe)); root != "" {
			return root, nil
		}
	}
	cwd, err := os.Getwd()
	if err == nil {
		if root := walkUp(cwd); root != "" {
			return root, nil
		}
	}
	return "", ErrRepoNotFound
}

func walkUp(start string) string {
	current := start
	for i := 0; i < 10; i++ {
		if fileExists(filepath.Join(current, "gradlew")) && fileExists(filepath.Join(current, "settings.gradle.kts")) {
			return current
		}
		parent := filepath.Dir(current)
		if parent == current {
			return ""
		}
		current = parent
	}
	return ""
}

func fileExists(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}
