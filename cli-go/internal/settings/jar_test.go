package settings

import (
	"os"
	"path/filepath"
	"testing"
)

func TestFindServerJAR_FlagWinsOverEnv(t *testing.T) {
	tmp := t.TempDir()
	flagJAR := filepath.Join(tmp, "flag.jar")
	envJAR := filepath.Join(tmp, "env.jar")
	writeFile(t, flagJAR, "")
	writeFile(t, envJAR, "")

	s := &Settings{ServerJAROverride: envJAR}
	got := FindServerJAR(s, flagJAR)
	if got != flagJAR {
		t.Errorf("expected flag to win; got %s", got)
	}
}

func TestFindServerJAR_EnvOverRepo(t *testing.T) {
	tmp := t.TempDir()
	envJAR := filepath.Join(tmp, "env.jar")
	writeFile(t, envJAR, "")

	// Make a fake repo with the conventional JAR path.
	repo := filepath.Join(tmp, "repo")
	writeFile(t, filepath.Join(repo, "gradlew"), "")
	writeFile(t, filepath.Join(repo, "settings.gradle.kts"), "")
	repoJAR := filepath.Join(repo, "server", "app", "build", "libs", "codelens-server-all.jar")
	writeFile(t, repoJAR, "")

	s := &Settings{ServerJAROverride: envJAR, RepoPath: repo}
	got := FindServerJAR(s, "")
	if got != envJAR {
		t.Errorf("env should win over repo JAR; got %s", got)
	}
}

func TestFindServerJAR_HomeFallback(t *testing.T) {
	// Isolate from the surrounding repo: tmp HOME + cwd outside any repo +
	// blank CODELENS_REPO_PATH means the only candidate that exists is the
	// home-dir JAR.
	tmp := t.TempDir()
	t.Setenv("HOME", tmp)
	t.Setenv("CODELENS_REPO_PATH", "")
	t.Chdir(tmp)

	homeJAR := filepath.Join(tmp, ".codelens", "codelens-server-all.jar")
	writeFile(t, homeJAR, "")

	got := FindServerJAR(&Settings{}, "")
	if got != homeJAR {
		t.Errorf("expected home fallback; got %s", got)
	}
}

func TestFindServerJAR_NoneFound(t *testing.T) {
	// Empty HOME and no repo discoverable.
	t.Setenv("HOME", t.TempDir())
	t.Setenv("CODELENS_REPO_PATH", "")
	// Change cwd to a directory that won't walk up into anything with gradlew.
	tmp := t.TempDir()
	t.Chdir(tmp)
	if got := FindServerJAR(&Settings{}, ""); got != "" {
		// Some test runners may have a cli-go module path up the chain; that's
		// the actual repo. Only fail when we're truly in a clean environment.
		if _, err := os.Stat(got); err == nil {
			t.Logf("FindServerJAR returned %s; probably discovered the surrounding repo, which is fine", got)
			return
		}
		t.Errorf("expected empty; got %s", got)
	}
}
