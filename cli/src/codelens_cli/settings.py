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
