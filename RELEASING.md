# Releasing codelens

The general release framework is `cc-plugins:release-workflows`; this file
documents what's specific to this repo.

## TL;DR

    /release-workflows:release v0.0.5

That's it. Everything else is automatic.

## What happens

1. **`release-workflows:release`** (LLM, local):
   - Verifies branch (`main`) + clean tree + CI green on HEAD
     (codelens's `build.yml` exposes a `ci-success` aggregate check; the
     skill's CI-green gate matches it by name)
   - Asks/confirms version
   - Drafts a CHANGELOG entry from `git log v<previous>..HEAD`, commits as
     `docs(changelog): vX.Y.Z entry`
   - Runs `scripts/release/update-version.sh X.Y.Z` → bumps **both**
     `version.txt` and `.claude-plugin/plugin.json` (delegated to the
     existing `scripts/set-version.sh`, with grep-verify after)
   - Commits as `chore(version): bump to X.Y.Z`
   - Tags `vX.Y.Z` (annotated) on the version commit
   - `git push --follow-tags` (admin bypasses the ruleset)

2. **`release.yaml`** (CI, on tag push `v*`) runs `ci-gate` → `release` → `apt-dispatch`:
   - **`ci-gate`**: polls the `ci-success` aggregate check (from `build.yml`) on
     the tagged commit and blocks the release until it is green — refusing to
     publish code that didn't pass CI (modeled on charliek/strix).
   - **`release`**:
     - Checks out, sets up JDK 21 + Gradle + Go
     - **Verifies `version.txt` and `.claude-plugin/plugin.json` both
       match the tag** (the safety net replacing the old `sync-version`
       job's contract; fails the release if either drifted)
     - Builds the server fat JAR (`./gradlew :server:app:shadowJar`)
     - Runs `go generate` to copy `version.txt` into the Go CLI's embed
       source (`cli/internal/version/version.txt`, gitignored)
     - Runs `go test ./...`
     - Mints a release-bot App token scoped to `charliek/homebrew-tap`
     - Runs `goreleaser release --clean`, which:
       - Builds 4 Go binaries (`darwin/linux` × `amd64/arm64`) with the
         embedded version
       - Tarballs each as `codelens_<os>_<arch>.tar.gz`, bundling the
         server JAR (`codelens-server-all.jar`) flattened to the archive
         root
       - Uploads tarballs + `checksums.txt` to the GitHub Release
       - Auto-generates release notes from commits
       - Pushes `Formula/codelens.rb` to `charliek/homebrew-tap` using
         the App-minted token (replaces the legacy `HOMEBREW_TAP_TOKEN`
         PAT)
   - **`apt-dispatch`**: after `release` succeeds, fires a `repository_dispatch`
     at `charliek/apt-charliek` so it republishes the apt index with the new
     `.deb`s (skips prereleases; self-heals if a dispatch is missed)

The maintainer runs step 1; everything else is automated.

## Version files this repo owns

`scripts/release/update-version.sh` (which delegates to
`scripts/set-version.sh`) bumps:

- **`version.txt` (repo root)** — read by Gradle, and copied by
  `go generate` into `cli/internal/version/version.txt` for the Go CLI
  to embed. The canonical version source.
- **`.claude-plugin/plugin.json`** — `version` field, read by Claude
  Code at install/update time.

NOT bumped:

- `gradle.properties` — Gradle properties file; no version field. The
  Gradle build reads `version.txt` instead.
- `pyproject.toml` — for the docs site only; has its own version
  cadence.
- `cli/internal/version/version.txt` — generated at build time by
  `go generate` (gitignored).

## Snapshot / dev versioning

Not used. Main between releases shows the last released version.

If you want `codelens version` between releases to show commits-past-tag
identity, add a `Commit={{.Commit}}` ldflag in `.goreleaser.yaml`'s
`builds[].ldflags`. Not currently wired.

## Secrets

| Secret | Purpose | Required? |
|---|---|---|
| `RELEASE_BOT_APP_ID` | `charliek-release-bot` GitHub App ID | required — minted at workflow time for the homebrew-tap push (passed to GoReleaser via `HOMEBREW_TAP_TOKEN` env) |
| `RELEASE_BOT_APP_KEY` | App private key (.pem) | required — same |

Retired (deleted from `gh secret list -R charliek/codelens` during the
convention adoption — confirm `gh secret list -R charliek/codelens`
returns only the `RELEASE_BOT_APP_*` pair, not just removed from the
workflow):

- `HOMEBREW_TAP_TOKEN` — replaced by the App-minted homebrew-tap token.
  GoReleaser still reads the env var named `HOMEBREW_TAP_TOKEN`; the
  workflow sets it from `steps.tap.outputs.token` instead of from
  `secrets`. If someone reintroduces `secrets.HOMEBREW_TAP_TOKEN` later
  without realizing the migration happened, it would silently re-shadow
  the App-minted value — that's why the secret itself needs to be gone
  from the secret store, not just unwired from the workflow.

## Branch protection

`main` is protected by ruleset `main` (id `16788896`) with rules
`deletion` + `non_fast_forward` (no `required_status_checks` — codelens's
`build.yml` runs `kotlin` + `go` jobs as separate checks with no single
aggregator). Bypass actors (added during the convention adoption):

- `charliek-release-bot` (App, type `Integration`) — lets the App push
  any future post-build asset updates back to codelens's main (no such
  step today; bypass-listed for future flexibility)
- Admin role (id `5`, type `RepositoryRole`) — lets
  `/release-workflows:release`'s push of the changelog + version commits
  + tag land

Inspect or edit at https://github.com/charliek/codelens/rules.

## App installation

The release-bot App must be installed on two repos:

- `charliek/codelens` itself (so the workflow's secrets resolve)
- `charliek/homebrew-tap` (so the minted token can push the formula)

Verify both via the `sanity-check-app.yml` workflow (Actions → Run
workflow). Each block must print the expected repo name.

## When things break

| Symptom | Cause | Fix |
|---|---|---|
| `git push` rejected | Pusher not in ruleset bypass | Confirm both the App and the admin role are in `main`'s ruleset `bypass_actors` |
| `release` job fails at `Verify version files match tag` | `version.txt` / `plugin.json` doesn't match the tag — `update-version.sh` didn't run, ran but the bump didn't land (silent sed no-op on a malformed plugin.json), or someone hand-edited one without the other | Re-bump locally with `./scripts/release/update-version.sh <ver>` (it grep-verifies both files), commit, push, cut a fresh patch tag. The buggy tag stays as an audit trail; don't force-update it. |
| GoReleaser fails at `brews` with `Bad credentials` | `RELEASE_BOT_APP_ID` unset OR App not installed on `homebrew-tap` | Confirm via `sanity-check-app.yml`'s homebrew-tap block; install the App on the tap if missing |
| `release` job fails at `Build server JAR` | Gradle build broken on tag SHA | Fix on a branch, merge, cut a fresh patch tag |
| `brew install charliek/tap/codelens` works but no JDK available | User hasn't installed a JDK 21+ | See the formula's `caveats:` block for install hints (SDKMAN, Homebrew, JAVA_HOME) |
| Claude Code installs old plugin version | `plugin.json` wasn't bumped before the tag (and the Verify step missed it, or was bypassed) | Bump manually with `scripts/release/update-version.sh <ver>` + commit + push to main, then redirect Claude Code users to reinstall |

## Break-glass recovery

### GoReleaser failed after some artifacts uploaded

GoReleaser's `mode: replace` in `.goreleaser.yaml` reuses the existing
Release on re-run. Cleanest path: re-run the failed GitHub Actions
workflow run from the UI; tarballs/checksums are re-uploaded, the
formula push retries.

```bash
RUN_ID=$(gh run list -R charliek/codelens --workflow release.yaml \
                     --limit 1 --json databaseId --jq '.[0].databaseId')
gh run rerun "${RUN_ID}" -R charliek/codelens --failed
```

### Version files drifted from the released tag

If main's `version.txt` or `plugin.json` doesn't match the latest
released tag (shouldn't happen with the new flow, but if it does):

```bash
./scripts/release/update-version.sh <released-version>
git add version.txt .claude-plugin/plugin.json
git commit -m "chore: align version files with v<released-version>"
git push origin main
```

## Adopting the convention (for new contributors)

If you're new to this repo and need to understand the release pipeline,
read [`cc-plugins/plugins/release-workflows/references/convention.md`](https://github.com/charliek/cc-plugins/blob/main/plugins/release-workflows/references/convention.md)
in the framework repo.

## Notes for this repo

- **No `version-check` separate job**: codelens's `release.yaml` is a
  single job (Gradle + Go + GoReleaser need to share state), so the
  tag-vs-manifest check is inlined as a step rather than a `needs:`-
  gated separate job.
- **`ci-gate` + `ci-success`**: `build.yml` exposes a `ci-success` aggregate
  check over `kotlin` + `go` + `e2e` + `release-snapshot` (the .deb validation),
  and `release.yaml`'s `ci-gate` job polls it on the tagged commit, blocking
  `release` until it is green (modeled on charliek/strix). `main`'s branch
  ruleset also requires `ci-success`, so a red PR can't merge.
- **No `--skip=validate` on GoReleaser**: the legacy flow used it
  because the workflow rewrote tracked `version.txt` at build time
  (dirty tree). Under the local-bump flow, version files are bumped
  BEFORE the tag, so the workflow doesn't dirty any tracked files
  (`go generate` writes to a gitignored copy).
- **`sync-version` job (removed)**: an earlier flow ran
  `scripts/set-version.sh` in CI after the release and bot-pushed the
  bumped version files back to main. That work moved LOCAL via
  `scripts/release/update-version.sh`, which the release skill runs
  before tagging.
