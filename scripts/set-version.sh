#!/usr/bin/env bash
# Set the canonical version across every file that hardcodes it:
#   - version.txt                  (read by Gradle, embedded into the Go CLI)
#   - .claude-plugin/plugin.json   (read by Claude Code at install/update time)
# Usage: scripts/set-version.sh 0.0.2
set -euo pipefail

version="${1:?usage: set-version.sh <X.Y.Z>}"
root="$(cd "$(dirname "$0")/.." && pwd)"

# Trailing newline keeps version.txt byte-identical when the value is unchanged.
printf '%s\n' "$version" > "$root/version.txt"

plugin="$root/.claude-plugin/plugin.json"
tmp="$(mktemp)"
sed -E 's/("version"[[:space:]]*:[[:space:]]*")[^"]*"/\1'"$version"'"/' "$plugin" > "$tmp"
mv "$tmp" "$plugin"

echo "version set to $version (version.txt, .claude-plugin/plugin.json)"
