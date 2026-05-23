package settings

import (
	"os"
	"path/filepath"
)

// FindServerJAR returns the path to the codelens-server-all.jar following
// the priority order locked in the plan:
//
//  1. --server-jar flag (caller passes via overrideFromFlag)
//  2. CODELENS_SERVER_JAR env var (already loaded into Settings.ServerJAROverride)
//  3. $CODELENS_REPO_PATH/server/app/build/libs/codelens-server-all.jar
//  4. walked-up repo root JAR (presence of gradlew + settings.gradle.kts)
//  5. ~/.codelens/codelens-server-all.jar (installed convention)
//
// Returns "" if none of the candidates point at an existing file.
func FindServerJAR(s *Settings, overrideFromFlag string) string {
	candidates := []string{}

	if overrideFromFlag != "" {
		candidates = append(candidates, overrideFromFlag)
	}
	if s != nil && s.ServerJAROverride != "" {
		candidates = append(candidates, s.ServerJAROverride)
	}
	if s != nil && s.RepoPath != "" {
		candidates = append(candidates, filepath.Join(s.RepoPath, "server", "app", "build", "libs", "codelens-server-all.jar"))
	}
	if repo, err := FindRepoPath(s); err == nil {
		candidates = append(candidates, filepath.Join(repo, "server", "app", "build", "libs", "codelens-server-all.jar"))
	}
	if home, err := os.UserHomeDir(); err == nil {
		candidates = append(candidates, filepath.Join(home, ".codelens", "codelens-server-all.jar"))
	}

	for _, c := range candidates {
		if fileExists(c) {
			return c
		}
	}
	return ""
}
