"""Application settings using Pydantic Settings."""

import os
from pathlib import Path
from typing import Optional

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

from codelens_cli.models import ServerMode


class PortRangeSettings(BaseSettings):
    """Port range configuration."""

    start: int = 8080
    end: int = 8180


class ServerSettings(BaseSettings):
    """Server configuration."""

    mode: ServerMode = ServerMode.AUTO
    idle_timeout: str = "30m"
    port_range: PortRangeSettings = Field(default_factory=PortRangeSettings)
    host: str = "127.0.0.1"


class OutputSettings(BaseSettings):
    """Output formatting configuration."""

    format: str = "auto"  # auto, text, json
    color: str = "auto"  # auto, always, never


class JavaSettings(BaseSettings):
    """Java/JVM configuration."""

    model_config = SettingsConfigDict(
        env_prefix="CODELENS_JAVA_",
    )

    home: Optional[str] = None
    opts: list[str] = Field(default_factory=list)


class AppSettings(BaseSettings):
    """Main application settings."""

    model_config = SettingsConfigDict(
        env_prefix="CODELENS_",
        env_nested_delimiter="__",
        case_sensitive=False,
    )

    server: ServerSettings = Field(default_factory=ServerSettings)
    output: OutputSettings = Field(default_factory=OutputSettings)
    java: JavaSettings = Field(default_factory=JavaSettings)
    repo_path: Optional[Path] = Field(None, alias="REPO_PATH")

    @field_validator("repo_path", mode="before")
    @classmethod
    def parse_repo_path(cls, v: Optional[str | Path]) -> Optional[Path]:
        """Parse repo path from environment."""
        if v is None:
            return None
        return Path(v) if isinstance(v, str) else v


def get_cache_dir() -> Path:
    """Get cache directory for CodeLens state."""
    cache_dir = Path.home() / ".cache" / "codelens"
    cache_dir.mkdir(parents=True, exist_ok=True)
    return cache_dir


def get_config_path() -> Path:
    """Get path to config file."""
    return Path.home() / ".config" / "codelens" / "config.yml"


def find_repo_path() -> Path:
    """Find the CodeLens repository root."""
    settings = AppSettings()

    # Check settings (from env var)
    if settings.repo_path:
        return settings.repo_path

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


def parse_sdkmanrc(sdkmanrc_path: Path) -> dict[str, str]:
    """Parse an .sdkmanrc file and return a dictionary of SDK names to versions.

    Args:
        sdkmanrc_path: Path to the .sdkmanrc file

    Returns:
        Dictionary mapping SDK names (e.g., 'java') to version strings (e.g., '21.0.9-amzn')
    """
    result: dict[str, str] = {}
    if not sdkmanrc_path.exists():
        return result

    for line in sdkmanrc_path.read_text().splitlines():
        line = line.strip()
        # Skip empty lines and comments
        if not line or line.startswith("#"):
            continue
        # Parse key=value pairs
        if "=" in line:
            key, value = line.split("=", 1)
            result[key.strip()] = value.strip()

    return result


def get_codelens_java_version() -> Optional[str]:
    """Get the Java version required by the codelens repo from its .sdkmanrc.

    Returns:
        The Java version string (e.g., '21.0.9-amzn') or None if not found.
    """
    try:
        repo_path = find_repo_path()
        sdkmanrc_path = repo_path / ".sdkmanrc"
        sdk_config = parse_sdkmanrc(sdkmanrc_path)
        return sdk_config.get("java")
    except (RuntimeError, OSError):
        return None


def find_sdkman_java(version: str) -> Optional[Path]:
    """Find a Java installation in SDKMAN's candidates directory.

    Args:
        version: The Java version string (e.g., '21.0.9-amzn')

    Returns:
        Path to the Java home directory, or None if not found.
    """
    sdkman_java_dir = Path.home() / ".sdkman" / "candidates" / "java"

    if not sdkman_java_dir.exists():
        return None

    # Try exact match first
    exact_match = sdkman_java_dir / version
    if exact_match.exists() and (exact_match / "bin" / "java").exists():
        return exact_match

    # Try fuzzy match: look for directories starting with the major version
    # This handles cases where version string format varies slightly
    major_version = version.split(".")[0]  # Extract "21" from "21.0.9-amzn"

    for java_dir in sdkman_java_dir.iterdir():
        if java_dir.is_dir() and java_dir.name.startswith(f"{major_version}."):
            if (java_dir / "bin" / "java").exists():
                return java_dir

    return None


def resolve_codelens_java_home() -> Optional[Path]:
    """Resolve the Java home directory for running the codelens server JAR.

    Attempts to find the Java installation that matches the codelens repo's
    .sdkmanrc configuration in SDKMAN's candidates directory.

    Returns:
        Path to Java home directory if found via SDKMAN, None otherwise.
    """
    # Get required version from codelens .sdkmanrc
    java_version = get_codelens_java_version()
    if not java_version:
        return None

    # Find it in SDKMAN
    java_home = find_sdkman_java(java_version)
    return java_home
