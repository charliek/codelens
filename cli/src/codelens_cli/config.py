"""Configuration management for CodeLens CLI."""

import os
from pathlib import Path
from typing import Any

import yaml

DEFAULT_CONFIG = {
    "server": {
        "mode": "auto",
        "idle_timeout": "30m",
        "port_range": {"start": 8080, "end": 8180},
        "host": "127.0.0.1",
    },
    "output": {
        "format": "auto",
        "color": "auto",
    },
    "java": {
        "home": None,
        "opts": [],
    },
}


def get_config_path() -> Path:
    """Get path to config file."""
    return Path.home() / ".config" / "codelens" / "config.yml"


def load_config() -> dict[str, Any]:
    """Load configuration from file, with defaults."""
    config = DEFAULT_CONFIG.copy()
    config_path = get_config_path()

    if config_path.exists():
        with open(config_path) as f:
            user_config = yaml.safe_load(f) or {}
            _deep_merge(config, user_config)

    # Environment variable overrides
    if mode := os.environ.get("CODELENS_SERVER_MODE"):
        config["server"]["mode"] = mode
    if timeout := os.environ.get("CODELENS_IDLE_TIMEOUT"):
        config["server"]["idle_timeout"] = timeout

    return config


def _deep_merge(base: dict, override: dict) -> None:
    """Deep merge override into base."""
    for key, value in override.items():
        if key in base and isinstance(base[key], dict) and isinstance(value, dict):
            _deep_merge(base[key], value)
        else:
            base[key] = value


def get_cache_dir() -> Path:
    """Get cache directory for CodeLens state."""
    cache_dir = Path.home() / ".cache" / "codelens"
    cache_dir.mkdir(parents=True, exist_ok=True)
    return cache_dir


def get_project_path(project: str | None) -> Path:
    """Get project path from argument or current directory."""
    if project:
        path = Path(project).resolve()
    else:
        path = Path.cwd()

    # Validate it's a Gradle project
    if not path.exists():
        raise typer.Exit(code=3)

    has_build_file = (path / "build.gradle").exists() or (path / "build.gradle.kts").exists()
    if not has_build_file:
        from rich.console import Console
        console = Console(stderr=True)
        console.print(f"[red]Error:[/red] No build.gradle or build.gradle.kts found in {path}")
        console.print("\nCodeLens requires a Gradle project directory.")
        console.print(f"\nTry: [cyan]cd /path/to/your/project[/cyan]")
        raise typer.Exit(code=3)

    return path


def find_repo_path() -> Path:
    """Find the CodeLens repository root."""
    # Check environment variable
    if env_path := os.environ.get("CODELENS_REPO_PATH"):
        return Path(env_path)

    # Walk up from this file to find gradlew
    current = Path(__file__).resolve().parent
    for _ in range(10):  # Max 10 levels up
        if (current / "gradlew").exists() and (current / "settings.gradle.kts").exists():
            return current
        parent = current.parent
        if parent == current:
            break
        current = parent

    raise RuntimeError(
        "Could not find CodeLens repository. "
        "Set CODELENS_REPO_PATH environment variable."
    )
