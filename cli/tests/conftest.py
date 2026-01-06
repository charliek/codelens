"""Pytest fixtures for CodeLens CLI tests."""

import tempfile
from datetime import datetime
from pathlib import Path
from typing import Generator
from unittest.mock import MagicMock, patch

import pytest

from codelens_cli.models import ProjectStatus, ServerMode, ServerState


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


@pytest.fixture
def mock_server_state() -> ServerState:
    """Pre-configured ServerState for testing."""
    return ServerState(
        pid=12345,
        port=8080,
        host="127.0.0.1",
        projectPath="/test/project",
        projectName="test-project",
        startedAt=datetime.now(),
        lastActivityAt=datetime.now(),
        idleTimeout="30m",
        status=ProjectStatus.READY,
        serverMode=ServerMode.GRADLE,
        version="0.1.0",
    )


@pytest.fixture
def mock_services(
    temp_project_dir: Path, mock_server_state: ServerState
) -> Generator[tuple[MagicMock, MagicMock, MagicMock], None, None]:
    """Mock ServiceContainer with configured services.

    Yields tuple of (mock_container, mock_server_service, mock_project_service)
    """
    with patch("codelens_cli.commands.common.ServiceContainer") as mock_container:
        mock_server_service = MagicMock()
        mock_project_service = MagicMock()

        mock_container.server_service.return_value = mock_server_service
        mock_container.project_service.return_value = mock_project_service
        mock_project_service.get_project_path.return_value = temp_project_dir
        mock_server_service.find_server.return_value = mock_server_state

        yield mock_container, mock_server_service, mock_project_service


@pytest.fixture
def mock_client() -> Generator[MagicMock, None, None]:
    """Mock CodeLensClient for testing API calls."""
    with patch("codelens_cli.commands.common.CodeLensClient") as mock_client_class:
        mock_instance = MagicMock()
        mock_client_class.return_value = mock_instance
        yield mock_instance
