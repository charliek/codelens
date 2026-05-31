#!/usr/bin/env bash
# Bump codelens's release version.
#
# Codelens has TWO source-tree version manifests, both kept in lockstep:
#
#   * version.txt (repo root) — read by Gradle's build, and copied by
#     `go generate` into cli/internal/version/version.txt for the Go CLI
#     to embed via `embed.String`. The "canonical" version source.
#
#   * .claude-plugin/plugin.json — read by Claude Code at install/update
#     time. Drifts independently if not bumped together.
#
# This script bumps both by delegating to the existing scripts/set-version.sh
# (which writes both), then grep-verifies that the bump actually landed in
# both files. The grep-back is the safety net: scripts/set-version.sh's
# sed for plugin.json silently no-ops if the "version" field is missing or
# formatted differently than expected, which would let the release skill
# tag a stale plugin.json.
#
# Contract (see cc-plugins:release-workflows references/update-version/README.md):
#   - one arg: semver string, no `v` prefix
#   - idempotent (same-version re-run leaves the tree unchanged)
#   - no network
#   - verifies its own work (explicit grep-back here)
#   - does not `git add` (release skill stages + commits)

set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <X.Y.Z>   e.g. $0 0.0.5" >&2
  exit 2
fi
V="$1"

if [[ ! "$V" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.-]+)?$ ]]; then
  echo "error: '$V' is not semver (X.Y.Z or X.Y.Z-suffix)" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
VERSION_TXT="${ROOT}/version.txt"
PLUGIN_JSON="${ROOT}/.claude-plugin/plugin.json"

# Delegate to the existing in-repo bumper.
"$(dirname "$0")/../set-version.sh" "$V"

# Verify both bumps landed. The grep-back closes the silent-failure gap
# in sed-based bumpers (see cc-plugins:release-workflows convention).

if [ "$(cat "${VERSION_TXT}")" != "${V}" ]; then
  echo "error: version.txt did not bump to ${V}" >&2
  exit 1
fi

# Use jq to anchor to the top-level .version field rather than a regex
# that could match nested "version" fields (e.g. in a future
# "dependencies" or "repositories" block). The cc-plugins template
# specifically recommends jq for plugin.json checks for this reason.
if [ "$(jq -r '.version' "${PLUGIN_JSON}")" != "${V}" ]; then
  echo "error: plugin.json top-level .version did not bump to ${V}" >&2
  exit 1
fi
