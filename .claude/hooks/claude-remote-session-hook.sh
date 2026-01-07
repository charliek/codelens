#!/bin/bash
# =============================================================================
# Claude Code Remote Environment Session Hook
# =============================================================================
#
# This script runs at the start of each Claude Code session in remote environments.
# It sets up the development environment for the codelens JVM/Gradle project.
#
# Phase 1: Core Development Tools (gh, bun, SDKMAN, Java, Python)
# Phase 2: Project Infrastructure (gradle-cc-proxy, CLI)
#
# =============================================================================

set -euo pipefail

# Only run in Claude Code remote environments
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
    exit 0
fi

# Ensure standard system paths are in PATH
export PATH="$HOME/.local/bin:$HOME/.bun/bin:$HOME/.sdkman/candidates/java/current/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH"

# Store the project root
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# =============================================================================
# PHASE 1: Core Development Tools
# =============================================================================

# -----------------------------------------------------------------------------
# GitHub CLI Setup
# -----------------------------------------------------------------------------
if ! command -v gh > /dev/null 2>&1 && [ ! -f "$HOME/.local/bin/gh" ]; then
    echo "Installing GitHub CLI..."
    GH_VERSION="2.63.2"
    GH_TARBALL="gh_${GH_VERSION}_linux_amd64.tar.gz"
    GH_URL="https://github.com/cli/cli/releases/download/v${GH_VERSION}/${GH_TARBALL}"

    cd /tmp
    if ! curl -fsSL --connect-timeout 10 --max-time 60 "$GH_URL" -o gh.tar.gz; then
        echo "ERROR: Failed to download GitHub CLI" >&2
        exit 1
    fi

    tar -xzf gh.tar.gz
    mkdir -p "$HOME/.local/bin"
    cp "gh_${GH_VERSION}_linux_amd64/bin/gh" "$HOME/.local/bin/"
    chmod +x "$HOME/.local/bin/gh"
    rm -rf gh.tar.gz "gh_${GH_VERSION}_linux_amd64"
    echo "GitHub CLI installed"
fi

# -----------------------------------------------------------------------------
# GitHub CLI Configuration (GH_REPO)
# -----------------------------------------------------------------------------
# Set default repository for gh commands so -R flag is not needed
export GH_REPO="charliek/codelens"

# -----------------------------------------------------------------------------
# Bun Setup (required for gradle-cc-proxy)
# -----------------------------------------------------------------------------
# The BUN_VERSION is set in .claude/settings.json

if command -v bun > /dev/null 2>&1 || [ -x "$HOME/.bun/bin/bun" ]; then
    # Bun is installed, check if we need to verify the version
    if [ -n "${BUN_VERSION:-}" ]; then
        CURRENT_BUN_VERSION=$(bun --version 2>/dev/null || echo "unknown")
        if [ "$CURRENT_BUN_VERSION" != "$BUN_VERSION" ]; then
            echo "WARNING: Bun $CURRENT_BUN_VERSION is installed, but $BUN_VERSION is specified" >&2
            echo "To update, run: curl -fsSL https://bun.sh/install | bash -s \"bun-v${BUN_VERSION}\"" >&2
        fi
    fi
else
    # Bun not installed, install it
    if [ -n "${BUN_VERSION:-}" ]; then
        echo "Installing Bun $BUN_VERSION..."
        if ! curl -fsSL --connect-timeout 10 --max-time 60 https://bun.sh/install 2>/dev/null | bash -s "bun-v${BUN_VERSION}" > /dev/null 2>&1; then
            echo "ERROR: Failed to install Bun $BUN_VERSION" >&2
            exit 1
        fi
    else
        echo "Installing Bun..."
        if ! curl -fsSL --connect-timeout 10 --max-time 60 https://bun.sh/install 2>/dev/null | bash > /dev/null 2>&1; then
            echo "ERROR: Failed to install Bun" >&2
            exit 1
        fi
    fi

    if [ -x "$HOME/.bun/bin/bun" ]; then
        echo "Bun installed"
    else
        echo "ERROR: Bun installation failed - binary not found" >&2
        exit 1
    fi
fi

# -----------------------------------------------------------------------------
# SDKMAN Setup
# -----------------------------------------------------------------------------
if [ ! -d "$HOME/.sdkman" ]; then
    echo "Installing SDKMAN..."
    curl -fsSL --connect-timeout 10 --max-time 60 "https://get.sdkman.io?rcupdate=false" | bash > /dev/null 2>&1
    echo "SDKMAN installed"
fi

# Source SDKMAN
if [ -f "$HOME/.sdkman/bin/sdkman-init.sh" ]; then
    export SDKMAN_DIR="$HOME/.sdkman"
    source "$HOME/.sdkman/bin/sdkman-init.sh"
fi

# -----------------------------------------------------------------------------
# Java Setup via SDKMAN
# -----------------------------------------------------------------------------
if [ -f "$PROJECT_ROOT/.sdkmanrc" ] && command -v sdk > /dev/null 2>&1; then
    JAVA_VERSION=$(grep "^java=" "$PROJECT_ROOT/.sdkmanrc" | cut -d'=' -f2)
    if [ -n "$JAVA_VERSION" ]; then
        if ! sdk list java 2>/dev/null | grep -q "$JAVA_VERSION.*installed"; then
            echo "Installing Java $JAVA_VERSION..."
            timeout 120 sdk install java "$JAVA_VERSION" < /dev/null > /dev/null 2>&1 || true
        fi
        sdk use java "$JAVA_VERSION" < /dev/null > /dev/null 2>&1 || true
    fi
fi

# -----------------------------------------------------------------------------
# Python Setup (via uv)
# -----------------------------------------------------------------------------
# Required for: CLI application
# The UV_PYTHON version is set in .claude/settings.json

if command -v uv > /dev/null 2>&1 && [ -n "${UV_PYTHON:-}" ]; then
    echo "Installing Python $UV_PYTHON..."
    if ! uv python install "$UV_PYTHON" > /dev/null 2>&1; then
        echo "ERROR: Failed to install Python $UV_PYTHON via uv" >&2
        echo "If running in a restricted environment, enable full network access or add github.com to your custom environment's allowed domains." >&2
        exit 1
    fi
    echo "Python $UV_PYTHON installed via uv"
fi

# =============================================================================
# PHASE 2: Project Infrastructure
# =============================================================================

# -----------------------------------------------------------------------------
# gradle-cc-proxy Setup
# -----------------------------------------------------------------------------
# Install and start the Gradle proxy for JWT-authenticated environments

if [ ! -d "$HOME/.local/gradle-cc-proxy" ]; then
    echo "Installing gradle-cc-proxy..."
    mkdir -p "$HOME/.local"
    cd "$HOME/.local"
    if git clone --depth 1 https://github.com/charliek/gradle-cc-proxy.git > /dev/null 2>&1; then
        cd gradle-cc-proxy
        if timeout 60 "$HOME/.local/gradle-cc-proxy/scripts/install.sh" > /dev/null 2>&1; then
            echo "gradle-cc-proxy installed"
        else
            echo "WARNING: gradle-cc-proxy installation failed" >&2
        fi
    else
        echo "WARNING: Failed to clone gradle-cc-proxy" >&2
    fi
fi

# Start the proxy
if [ -x "$HOME/.local/gradle-cc-proxy/scripts/start-proxy.sh" ]; then
    "$HOME/.local/gradle-cc-proxy/scripts/start-proxy.sh"
fi

# -----------------------------------------------------------------------------
# Project Dependencies
# -----------------------------------------------------------------------------
cd "$PROJECT_ROOT"

# CLI dependencies (Python via uv)
if command -v uv > /dev/null 2>&1 && [ -d "cli" ]; then
    echo "Installing CLI in editable mode..."
    cd "$PROJECT_ROOT/cli"
    if ! timeout 60 uv tool install --editable . > /dev/null 2>&1; then
        echo "WARNING: Failed to install CLI" >&2
    else
        echo "CLI installed"
    fi
    cd "$PROJECT_ROOT"
fi

echo "Claude Code remote environment setup complete"
