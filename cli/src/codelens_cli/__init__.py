"""CodeLens CLI - Ratpack migration analysis tool."""

from importlib.metadata import PackageNotFoundError, version as _installed_version
from pathlib import Path


def _read_version() -> str:
    """Return the CodeLens version.

    Prefers the installed package metadata. Falls back to the repo's
    `version.txt` when running from source (editable install / running
    tests in-tree). This keeps a single source of truth for the version
    across the Python CLI and the Kotlin server (both ultimately point
    at `version.txt`).
    """
    try:
        return _installed_version("codelens-cli")
    except PackageNotFoundError:
        pass

    # When invoked from the source tree, look for version.txt at the repo root.
    here = Path(__file__).resolve()
    # cli/src/codelens_cli/__init__.py -> cli/src/codelens_cli -> cli/src -> cli -> repo root
    candidate = here.parent.parent.parent.parent / "version.txt"
    if candidate.exists():
        return candidate.read_text().strip()

    return "0.0.0+unknown"


__version__ = _read_version()
