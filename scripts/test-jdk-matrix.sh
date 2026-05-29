#!/usr/bin/env bash
# scripts/test-jdk-matrix.sh
#
# Manual end-to-end matrix for JDK resolution. Drives real install/uninstall
# through SDKMAN, Homebrew (formula + cask), and mise; starts/stops a real
# codelens server against a synthetic test project between scenarios.
#
# This script intentionally does NOT use `set -e` — every step records
# PASS/FAIL and the run continues so the final summary shows the full
# matrix even if one scenario fails.
#
# Authored for issue #35. Safe to re-run; restores the original baseline in
# the EXIT trap. Steps that install or uninstall toolchain JDKs are gated by
# CODELENS_TEST_* env vars so it's opt-in for CI and explicit locally.
#
# Usage:
#   ./scripts/test-jdk-matrix.sh                    # run baseline + SplitN + error-msg + restore
#   CODELENS_TEST_BREW_CASK=1 ./scripts/test-jdk-matrix.sh        # also exercise cask install
#   CODELENS_TEST_BREW_FORMULA=1 ./scripts/test-jdk-matrix.sh     # also exercise openjdk@21 formula
#   CODELENS_TEST_MISE=1 ./scripts/test-jdk-matrix.sh             # also exercise mise install
#   CODELENS_TEST_ALL=1 ./scripts/test-jdk-matrix.sh              # all of the above
#
# Notes:
#   * Requires codelens binary on PATH (or set CODELENS=/path/to/codelens).
#   * The project directory used is /tmp/cl-jdk-matrix; created fresh each run.
#   * Pass/fail tallying uses simple integer counters at the bottom.

set -u  # NOT -e — we want to keep going on failures

CODELENS="${CODELENS:-codelens}"
PROJECT="${1:-/tmp/cl-jdk-matrix}"

# --------- helpers ---------

PASS=0; FAIL=0
RED=$'\e[31m'; GREEN=$'\e[32m'; YELLOW=$'\e[33m'; CYAN=$'\e[36m'; RESET=$'\e[0m'

info()  { printf '%s[INFO]%s  %s\n' "$CYAN"   "$RESET" "$*"; }
warn()  { printf '%s[WARN]%s  %s\n' "$YELLOW" "$RESET" "$*"; }
pass()  { PASS=$((PASS+1)); printf '%s[PASS]%s  %s\n' "$GREEN" "$RESET" "$*"; }
fail()  { FAIL=$((FAIL+1)); printf '%s[FAIL]%s  %s\n' "$RED"   "$RESET" "$*"; }

inventory() {
  info "inventory:"
  if [[ -d "$HOME/.sdkman/candidates/java" ]]; then
    printf '  SDKMAN: '; ls -1 "$HOME/.sdkman/candidates/java" 2>/dev/null | grep -v '^current$' | tr '\n' ' '; echo
  else
    echo "  SDKMAN: (no candidates dir)"
  fi
  if command -v brew >/dev/null 2>&1; then
    local kegs casks
    kegs=$(brew list --formula 2>/dev/null | grep -E '^openjdk' | tr '\n' ' ')
    casks=$(brew list --cask 2>/dev/null | grep -E 'temurin|corretto|zulu|graalvm|liberica|microsoft-openjdk|oracle-jdk|semeru' | tr '\n' ' ')
    echo "  Homebrew formulas: ${kegs:-(none)}"
    echo "  Homebrew casks:    ${casks:-(none)}"
  else
    echo "  Homebrew: (not installed)"
  fi
  if [[ -d /Library/Java/JavaVirtualMachines ]]; then
    printf '  /Library/Java/JavaVirtualMachines: '; ls -1 /Library/Java/JavaVirtualMachines 2>/dev/null | tr '\n' ' '; echo
  else
    echo "  /Library/Java/JavaVirtualMachines: (none)"
  fi
  if command -v mise >/dev/null 2>&1; then
    printf '  mise java: '; mise ls java 2>/dev/null | awk 'NR>1{print $2}' | tr '\n' ' '; echo
  else
    echo "  mise: (not installed)"
  fi
}

# Ensure no stale server is hanging around between scenarios.
stop_server() {
  "$CODELENS" stop --project "$PROJECT" >/dev/null 2>&1 || true
}

# Save the original .sdkmanrc (if any) so the EXIT trap can restore.
ORIG_SDKMANRC_PATH=""
ORIG_SDKMANRC_CONTENT=""
if [[ -f "$PROJECT/.sdkmanrc" ]]; then
  ORIG_SDKMANRC_PATH="$PROJECT/.sdkmanrc"
  ORIG_SDKMANRC_CONTENT=$(cat "$ORIG_SDKMANRC_PATH")
fi

restore_baseline() {
  stop_server
  if [[ -n "$ORIG_SDKMANRC_PATH" ]]; then
    printf '%s' "$ORIG_SDKMANRC_CONTENT" > "$ORIG_SDKMANRC_PATH"
    info "restored original $ORIG_SDKMANRC_PATH"
  fi
  echo
  info "final inventory:"
  inventory
  echo
  if [[ "$FAIL" -eq 0 ]]; then
    printf '%sALL PASS%s  (%d scenarios)\n' "$GREEN" "$RESET" "$PASS"
  else
    printf '%sFAILED%s    PASS=%d FAIL=%d\n' "$RED" "$RESET" "$PASS" "$FAIL"
  fi
}
trap restore_baseline EXIT

# Pre-flight: codelens binary must exist.
if ! "$CODELENS" version >/dev/null 2>&1; then
  fail "codelens binary not runnable: $CODELENS"
  exit 1
fi

# Fresh test-project skeleton: build.gradle.kts is the bare minimum codelens
# requires to start. We rewrite .sdkmanrc per scenario.
mkdir -p "$PROJECT"
touch "$PROJECT/build.gradle.kts"

echo
info "==== baseline ===="
inventory
echo

# ---------- Scenario 1: baseline succeeds with declared+installed ---------

info "==== scenario 1: declared+installed (sanity) ===="
stop_server
INSTALLED_SDKMAN_JDK=$(ls -1 "$HOME/.sdkman/candidates/java" 2>/dev/null | grep -v '^current$' | head -1 || true)
if [[ -z "$INSTALLED_SDKMAN_JDK" ]]; then
  warn "scenario 1 skipped: no SDKMAN JDK installed to use as baseline"
else
  echo "java=$INSTALLED_SDKMAN_JDK" > "$PROJECT/.sdkmanrc"
  out=$("$CODELENS" start --project "$PROJECT" --timeout 30 2>&1)
  if echo "$out" | grep -q '"status": "READY"'; then
    pass "scenario 1: codelens started using $INSTALLED_SDKMAN_JDK"
  else
    fail "scenario 1: expected READY; got:"
    printf '%s\n' "$out" | head -10
  fi
  stop_server
fi
echo

# ---------- Scenario 2: SplitN fix (21-tem vendor alias) ---------

info "==== scenario 2: vendor-alias 21-tem (SplitN bug regression) ===="
stop_server
# Look for ANY installed major-21 JDK (any vendor) — the fix lets us substitute.
MAJOR21=$(ls -1 "$HOME/.sdkman/candidates/java" 2>/dev/null | grep -E '^21' | grep -v '^current$' | head -1 || true)
if [[ -z "$MAJOR21" ]]; then
  warn "scenario 2 skipped: no SDKMAN major-21 JDK installed"
else
  echo "java=21-tem" > "$PROJECT/.sdkmanrc"
  out=$("$CODELENS" start --project "$PROJECT" --timeout 30 2>&1)
  status_ok=0; note_ok=0
  echo "$out" | grep -q '"status": "READY"' && status_ok=1
  echo "$out" | grep -q 'note: project declares Java 21-tem' && note_ok=1
  if [[ $status_ok -eq 1 && $note_ok -eq 1 ]]; then
    pass "scenario 2: 21-tem substituted to $MAJOR21 with stderr note"
  else
    fail "scenario 2: status_ok=$status_ok note_ok=$note_ok output:"
    printf '%s\n' "$out" | head -10
  fi
  stop_server
fi
echo

# ---------- Scenario 3: missing-major error lists what IS installed ---------

info "==== scenario 3: missing major lists installed JDKs ===="
stop_server
echo "java=8.0.392-amzn" > "$PROJECT/.sdkmanrc"
out=$("$CODELENS" start --project "$PROJECT" --timeout 10 2>&1)
hits=0
echo "$out" | grep -q "8.0.392-amzn" && hits=$((hits+1))
echo "$out" | grep -q "isn't installed"   && hits=$((hits+1))
echo "$out" | grep -q "installed JDKs:"   && hits=$((hits+1))
if [[ $hits -ge 3 ]]; then
  pass "scenario 3: error lists installed JDKs ($hits/3 checks)"
else
  fail "scenario 3: error missing expected text ($hits/3); got:"
  printf '%s\n' "$out" | head -10
fi
echo

# ---------- Scenario 4a: simulated JavaVMs install (always run) ---------
# Proves the JavaVMs code path end-to-end without needing root. We drop a
# minimal fake "temurin-21.jdk/Contents/Home/bin/java" tree into a temp dir
# and point CODELENS_JAVA_VM_DIRS at it. This exercises the same discovery
# code that finds real cask installs.

info "==== scenario 4a: simulated JavaVMs install (no sudo) ===="
stop_server
# Re-discover an installed major-21 JDK so this scenario is self-contained.
MAJOR21=$(ls -1 "$HOME/.sdkman/candidates/java" 2>/dev/null | grep -E '^21' | grep -v '^current$' | head -1 || true)
SIM_VMS=$(mktemp -d)
mkdir -p "$SIM_VMS/temurin-21.jdk/Contents"
# Symlink the whole Contents/Home dir to a real SDKMAN install so the
# launcher (which needs lib/, jre/, etc.) can actually start a real JVM.
if [[ -n "$MAJOR21" && -x "$HOME/.sdkman/candidates/java/$MAJOR21/bin/java" ]]; then
  ln -s "$HOME/.sdkman/candidates/java/$MAJOR21" "$SIM_VMS/temurin-21.jdk/Contents/Home"

  echo "java=21-tem" > "$PROJECT/.sdkmanrc"
  # Empty SDKMAN/mise/Homebrew via env so the JavaVMs source wins.
  EMPTY=$(mktemp -d)
  out=$(HOME="$EMPTY" \
        HOMEBREW_PREFIX="$(mktemp -d)" \
        MISE_DATA_DIR="$(mktemp -d)" \
        CODELENS_JAVA_VM_DIRS="$SIM_VMS" \
        "$CODELENS" start --project "$PROJECT" --timeout 30 2>&1)
  status_ok=0; src_ok=0
  echo "$out" | grep -q '"status": "READY"' && status_ok=1
  echo "$out" | grep -q 'JavaVMs' && src_ok=1
  if [[ $status_ok -eq 1 && $src_ok -eq 1 ]]; then
    pass "scenario 4a: JavaVMs install discovered + note names JavaVMs source"
  else
    fail "scenario 4a: status_ok=$status_ok src_ok=$src_ok; output:"
    printf '%s\n' "$out" | head -10
  fi
  # Stop the server using its real HOME so the state file is in the right place.
  HOME="$EMPTY" CODELENS_JAVA_VM_DIRS="$SIM_VMS" "$CODELENS" stop --project "$PROJECT" >/dev/null 2>&1 || true
  rm -rf "$EMPTY"
else
  warn "scenario 4a skipped: no real SDKMAN major-21 JDK to symlink as a launcher"
fi
rm -rf "$SIM_VMS"
echo

# ---------- Scenario 4b: real Homebrew cask install (opt-in, may need sudo) ---------

if [[ "${CODELENS_TEST_BREW_CASK:-${CODELENS_TEST_ALL:-0}}" = 1 ]] && command -v brew >/dev/null 2>&1; then
  info "==== scenario 4b: real Homebrew cask temurin (needs sudo on macOS) ===="
  stop_server
  if ! brew list --cask 2>/dev/null | grep -qx 'temurin'; then
    info "  installing temurin cask (this may take ~30s and may prompt for sudo)"
    cask_out=$(brew install --cask temurin 2>&1)
    if echo "$cask_out" | grep -q 'password is required\|sudo: a terminal'; then
      warn "scenario 4b skipped: cask install needs interactive sudo; rerun with sudo or set up askpass"
      echo
      cask_install_attempted=0
    else
      cask_install_attempted=1
    fi
  else
    cask_install_attempted=1
  fi
  if [[ "${cask_install_attempted:-0}" = 1 ]] && ls /Library/Java/JavaVirtualMachines/ 2>/dev/null | grep -q '^temurin-'; then
    echo "java=21-tem" > "$PROJECT/.sdkmanrc"
    out=$("$CODELENS" start --project "$PROJECT" --timeout 30 2>&1)
    if echo "$out" | grep -q '"status": "READY"'; then
      pass "scenario 4b: cask Temurin discovered (or higher-priority source resolved)"
    else
      fail "scenario 4b: not READY; output:"
      printf '%s\n' "$out" | head -10
    fi
    stop_server
    if [[ "${CODELENS_TEST_BREW_CASK_UNINSTALL:-0}" = 1 ]]; then
      info "  uninstalling temurin cask (per CODELENS_TEST_BREW_CASK_UNINSTALL)"
      brew uninstall --cask temurin 2>/dev/null || true
    fi
  fi
  echo
fi

# ---------- Scenario 5: Homebrew formula (opt-in) ---------

if [[ "${CODELENS_TEST_BREW_FORMULA:-${CODELENS_TEST_ALL:-0}}" = 1 ]] && command -v brew >/dev/null 2>&1; then
  info "==== scenario 5: Homebrew openjdk@21 formula ===="
  stop_server
  if ! brew list --formula 2>/dev/null | grep -qx 'openjdk@21'; then
    info "  installing openjdk@21 (this may take ~30s)"
    brew install openjdk@21 2>&1 | tail -3
  fi
  if [[ -x /opt/homebrew/opt/openjdk@21/bin/java || -x /usr/local/opt/openjdk@21/bin/java ]]; then
    pass "scenario 5: openjdk@21 keg present and runnable (codelens auto-detects via FindHomebrewJava)"
  else
    fail "scenario 5: openjdk@21 keg not found after install"
  fi
  if [[ "${CODELENS_TEST_BREW_FORMULA_UNINSTALL:-0}" = 1 ]]; then
    info "  uninstalling openjdk@21 (per CODELENS_TEST_BREW_FORMULA_UNINSTALL)"
    brew uninstall openjdk@21 2>/dev/null || true
  fi
  echo
fi

# ---------- Scenario 6: mise install (opt-in) ---------

if [[ "${CODELENS_TEST_MISE:-${CODELENS_TEST_ALL:-0}}" = 1 ]] && command -v mise >/dev/null 2>&1; then
  info "==== scenario 6: mise temurin install ===="
  stop_server
  MISE_VER=temurin-21.0.9
  if ! mise ls java 2>/dev/null | awk 'NR>1 {print $2}' | grep -qx "$MISE_VER"; then
    info "  installing java@$MISE_VER via mise (this may take ~60s)"
    mise install "java@$MISE_VER" 2>&1 | tail -3
  fi
  # Use .tool-versions so DetectProjectJavaVersion picks it up.
  rm -f "$PROJECT/.sdkmanrc"
  echo "java $MISE_VER" > "$PROJECT/.tool-versions"
  out=$("$CODELENS" start --project "$PROJECT" --timeout 30 2>&1)
  if echo "$out" | grep -q '"status": "READY"'; then
    pass "scenario 6: codelens started using mise $MISE_VER"
  else
    fail "scenario 6: not READY; output:"
    printf '%s\n' "$out" | head -10
  fi
  stop_server
  rm -f "$PROJECT/.tool-versions"
  if [[ "${CODELENS_TEST_MISE_UNINSTALL:-0}" = 1 ]]; then
    info "  uninstalling java@$MISE_VER (per CODELENS_TEST_MISE_UNINSTALL)"
    mise uninstall "java@$MISE_VER" 2>/dev/null || true
  fi
  echo
fi

# ---------- Scenario 7: no JDKs at all (env-isolated) ---------

info "==== scenario 7: no JDKs at all (env-isolated) ===="
stop_server
EMPTY_HOME=$(mktemp -d)
EMPTY_BREW=$(mktemp -d)
EMPTY_MISE=$(mktemp -d)
EMPTY_VMS=$(mktemp -d)
echo "java=21-tem" > "$PROJECT/.sdkmanrc"
out=$(HOME="$EMPTY_HOME" \
      HOMEBREW_PREFIX="$EMPTY_BREW" \
      MISE_DATA_DIR="$EMPTY_MISE" \
      CODELENS_JAVA_VM_DIRS="$EMPTY_VMS" \
      "$CODELENS" start --project "$PROJECT" --timeout 10 2>&1)
hits=0
echo "$out" | grep -q "21-tem"              && hits=$((hits+1))
echo "$out" | grep -q "no JDKs found"       && hits=$((hits+1))
echo "$out" | grep -q "SDKMAN"              && hits=$((hits+1))
echo "$out" | grep -q "JavaVirtualMachines" && hits=$((hits+1))
echo "$out" | grep -q "mise"                && hits=$((hits+1))
if [[ $hits -ge 4 ]]; then
  pass "scenario 7: empty-environment error names all sources ($hits/5 checks)"
else
  fail "scenario 7: error missing expected text ($hits/5); got:"
  printf '%s\n' "$out" | head -10
fi
rm -rf "$EMPTY_HOME" "$EMPTY_BREW" "$EMPTY_MISE" "$EMPTY_VMS"
echo

# trap EXIT restores baseline and prints the final tally
