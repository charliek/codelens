"""Pytest fixtures for CodeLens CLI tests."""

import tempfile
from pathlib import Path
from typing import Generator

import pytest


@pytest.fixture
def temp_project_dir() -> Generator[Path, None, None]:
    """Create a temporary directory with a build.gradle.kts file."""
    with tempfile.TemporaryDirectory() as tmpdir:
        project_path = Path(tmpdir)
        (project_path / "build.gradle.kts").write_text("// Test project")
        yield project_path


@pytest.fixture
def temp_cache_dir(monkeypatch: pytest.MonkeyPatch) -> Generator[Path, None, None]:
    """Create a temporary cache directory and set it as the default."""
    with tempfile.TemporaryDirectory() as tmpdir:
        cache_path = Path(tmpdir)
        # This would need to be updated to properly mock get_cache_dir
        yield cache_path
