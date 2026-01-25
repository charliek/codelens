"""Application settings using Pydantic Settings."""

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
        if (current / "gradlew").exists() and (
            current / "settings.gradle.kts"
        ).exists():
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


def detect_project_java_version(project_path: Path) -> Optional[str]:
    """Detect the Java version required by a target project.

    Checks in priority order:
    1. .sdkmanrc file
    2. .java-version file
    3. gradle.properties (org.gradle.java.home - extracts version from SDKMAN path)

    Args:
        project_path: Path to the target project

    Returns:
        Java version string (e.g., '11.0.28-tem') or None if not detected
    """
    import re

    # 1. Check .sdkmanrc
    sdkmanrc = project_path / ".sdkmanrc"
    if sdkmanrc.exists():
        config = parse_sdkmanrc(sdkmanrc)
        if "java" in config:
            return config["java"]

    # 2. Check .java-version
    java_version_file = project_path / ".java-version"
    if java_version_file.exists():
        version = java_version_file.read_text().strip()
        if version:
            return version

    # 3. Check gradle.properties for org.gradle.java.home
    gradle_props = project_path / "gradle.properties"
    if gradle_props.exists():
        for line in gradle_props.read_text().splitlines():
            line = line.strip()
            if line.startswith("org.gradle.java.home="):
                java_home = line.split("=", 1)[1].strip()
                # Try to extract version from SDKMAN path
                match = re.search(r"candidates/java/([^/]+)", java_home)
                if match:
                    return match.group(1)

    return None


def resolve_project_java_home(project_path: Path) -> Optional[Path]:
    """Resolve the Java home directory for a target project.

    Detects the project's required Java version and locates the JDK in SDKMAN.

    Args:
        project_path: Path to the target project

    Returns:
        Path to Java home directory if found, None otherwise
    """

    # First, try to detect version from project files and find in SDKMAN
    version = detect_project_java_version(project_path)
    if version:
        java_home = find_sdkman_java(version)
        if java_home:
            return java_home

    # Check for explicit gradle.properties java.home path
    gradle_props = project_path / "gradle.properties"
    if gradle_props.exists():
        for line in gradle_props.read_text().splitlines():
            if line.strip().startswith("org.gradle.java.home="):
                java_home_str = line.split("=", 1)[1].strip()
                java_home_path = Path(java_home_str).expanduser()
                if (
                    java_home_path.exists()
                    and (java_home_path / "bin" / "java").exists()
                ):
                    return java_home_path

    return None


def get_gradle_version(project_path: Path) -> Optional[str]:
    """Get the Gradle version from gradle-wrapper.properties.

    Args:
        project_path: Path to the target project

    Returns:
        Gradle version string (e.g., '7.6.1') or None if not found
    """
    import re

    wrapper_props = project_path / "gradle" / "wrapper" / "gradle-wrapper.properties"
    if not wrapper_props.exists():
        return None

    for line in wrapper_props.read_text().splitlines():
        if "distributionUrl" in line:
            # Format: distributionUrl=https\://services.gradle.org/distributions/gradle-7.6.1-bin.zip
            match = re.search(r"gradle-(\d+\.\d+(?:\.\d+)?)", line)
            if match:
                return match.group(1)

    return None


def needs_older_java_for_gradle(project_path: Path) -> bool:
    """Check if the project's Gradle version requires an older Java.

    Gradle version to max Java mapping:
    - Gradle 8.5+ supports Java 21
    - Gradle 8.0-8.4 supports Java 20
    - Gradle 7.x supports Java 19
    - Gradle 6.x supports Java 15

    Args:
        project_path: Path to the target project

    Returns:
        True if the project needs an older Java for Gradle operations
    """
    gradle_version = get_gradle_version(project_path)
    if not gradle_version:
        return False  # Unknown, assume compatible

    parts = gradle_version.split(".")
    major = int(parts[0])
    minor = int(parts[1]) if len(parts) > 1 else 0

    # Gradle 8.5+ supports Java 21
    if major > 8 or (major == 8 and minor >= 5):
        return False

    # Older versions need older Java
    return True
