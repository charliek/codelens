"""Characterization tests for lifecycle commands.

Locks the user-observable contract of ``codelens start | stop | status |
restart | refresh | list``: exit codes, JSON output shapes, and the
non-TTY-implies-JSON behavior. These tests intentionally use the public
CLI surface via ``CliRunner`` rather than calling functions directly --
they should pass against any future port (Go, Kotlin, etc.) that aims
to be a drop-in replacement.

The exit-code contract documented here lives in
``cli/src/codelens_cli/errors.py``:

  0 SUCCESS
  4 SERVER_ERROR (generic failure during start/restart/refresh)
  5 TIMEOUT     (start/restart did not see CODELENS_READY in time)
  7 NOT_RUNNING (refresh against no server)
"""

from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from unittest.mock import MagicMock, patch

import pytest
from typer.testing import CliRunner

from codelens_cli.errors import ExitCode
from codelens_cli.main import app
from codelens_cli.models import (
    ProjectInfo,
    ProjectStatus,
    ServerMode,
    ServerState,
)


runner = CliRunner()


def _state(project_path: Path, status: ProjectStatus = ProjectStatus.READY) -> ServerState:
    now = datetime.now(timezone.utc)
    return ServerState(
        pid=12345,
        port=8080,
        host="127.0.0.1",
        projectPath=project_path,
        projectName=project_path.name,
        startedAt=now,
        lastActivityAt=now,
        idleTimeout="30m",
        status=status,
        serverMode=ServerMode.JAR,
        version="9.9.9-test",
    )


@pytest.fixture
def lifecycle_mocks(temp_project_dir: Path):
    """Patches everything lifecycle.py reaches through:

    * ServiceContainer.server_service / project_service
    * ServerService (used directly by `list`)
    * CodeLensClient (used by `status`)
    """
    with (
        patch("codelens_cli.commands.lifecycle.ServiceContainer") as mock_container,
        patch("codelens_cli.commands.lifecycle.ServerService") as mock_server_service_cls,
        patch("codelens_cli.commands.lifecycle.CodeLensClient") as mock_client_cls,
    ):
        mock_server = MagicMock()
        mock_project = MagicMock()
        mock_container.server_service.return_value = mock_server
        mock_container.project_service.return_value = mock_project
        mock_project.get_project_path.return_value = temp_project_dir

        # `list_servers` instantiates ServerService() directly.
        mock_list_server = MagicMock()
        mock_server_service_cls.return_value = mock_list_server

        # `status` instantiates CodeLensClient(host, port).
        mock_client = MagicMock()
        mock_client_cls.return_value = mock_client

        yield {
            "container": mock_container,
            "server_service": mock_server,
            "project_service": mock_project,
            "list_server_service": mock_list_server,
            "client": mock_client,
            "project_path": temp_project_dir,
        }


def _json_from(result_output: str) -> Any:
    """Extract the first JSON document from CLI output."""
    return json.loads(result_output)


# ============================== start ==============================


class TestStart:
    def test_already_ready_does_not_respawn(self, lifecycle_mocks: dict) -> None:
        existing = _state(lifecycle_mocks["project_path"], ProjectStatus.READY)
        lifecycle_mocks["server_service"].find_server.return_value = existing

        result = runner.invoke(app, ["start", "--json"])

        assert result.exit_code == ExitCode.SUCCESS
        lifecycle_mocks["server_service"].start_server.assert_not_called()
        payload = _json_from(result.stdout)
        assert payload["status"] == "READY"
        assert payload["port"] == 8080
        assert payload["serverMode"] == "jar"

    def test_starts_when_no_existing_server(self, lifecycle_mocks: dict) -> None:
        lifecycle_mocks["server_service"].find_server.return_value = None
        started = _state(lifecycle_mocks["project_path"], ProjectStatus.READY)
        # `asyncio.run` will await the coroutine. We return an awaitable result
        # by making start_server an AsyncMock-like coroutine.
        async def fake_start_server(*args, **kwargs):
            return started

        lifecycle_mocks["server_service"].start_server.side_effect = fake_start_server

        result = runner.invoke(app, ["start", "--json"])

        assert result.exit_code == ExitCode.SUCCESS
        payload = _json_from(result.stdout)
        assert payload["status"] == "READY"

    def test_start_existing_starting_status_triggers_spawn(self, lifecycle_mocks: dict) -> None:
        """If the persisted state is NOT in READY state, start_server is still called.

        This matches the documented behavior at lifecycle.py:56 -- the
        already-running short-circuit only triggers on `status == READY`.
        """
        existing = _state(lifecycle_mocks["project_path"], ProjectStatus.STARTING)
        lifecycle_mocks["server_service"].find_server.return_value = existing
        started = _state(lifecycle_mocks["project_path"], ProjectStatus.READY)

        async def fake_start_server(*args, **kwargs):
            return started

        lifecycle_mocks["server_service"].start_server.side_effect = fake_start_server

        result = runner.invoke(app, ["start", "--json"])

        assert result.exit_code == ExitCode.SUCCESS
        lifecycle_mocks["server_service"].start_server.assert_called_once()

    def test_start_timeout_returns_exit_5(self, lifecycle_mocks: dict) -> None:
        lifecycle_mocks["server_service"].find_server.return_value = None

        async def fake_start_server(*args, **kwargs):
            raise TimeoutError("did not see CODELENS_READY")

        lifecycle_mocks["server_service"].start_server.side_effect = fake_start_server

        result = runner.invoke(app, ["start", "--json"])

        assert result.exit_code == ExitCode.TIMEOUT
        assert result.exit_code == 5

    def test_start_generic_failure_returns_exit_4(self, lifecycle_mocks: dict) -> None:
        lifecycle_mocks["server_service"].find_server.return_value = None

        async def fake_start_server(*args, **kwargs):
            raise RuntimeError("classpath resolution failed")

        lifecycle_mocks["server_service"].start_server.side_effect = fake_start_server

        result = runner.invoke(app, ["start", "--json"])

        assert result.exit_code == ExitCode.SERVER_ERROR
        assert result.exit_code == 4


# ============================== stop ==============================


class TestStop:
    def test_stop_when_running_returns_stopped_true(self, lifecycle_mocks: dict) -> None:
        lifecycle_mocks["server_service"].stop_server.return_value = True

        result = runner.invoke(app, ["stop", "--json"])

        assert result.exit_code == ExitCode.SUCCESS
        payload = _json_from(result.stdout)
        assert payload["stopped"] is True
        assert "project" in payload

    def test_stop_when_not_running_returns_exit_0_with_stopped_false(self, lifecycle_mocks: dict) -> None:
        """`stop` is intentionally idempotent: no server == exit 0, not exit 7."""
        lifecycle_mocks["server_service"].stop_server.return_value = False

        result = runner.invoke(app, ["stop", "--json"])

        assert result.exit_code == 0
        payload = _json_from(result.stdout)
        assert payload["stopped"] is False

    def test_stop_force_propagates(self, lifecycle_mocks: dict) -> None:
        lifecycle_mocks["server_service"].stop_server.return_value = True

        runner.invoke(app, ["stop", "--force"])

        lifecycle_mocks["server_service"].stop_server.assert_called_with(
            lifecycle_mocks["project_path"], force=True
        )


# ============================== status ==============================


class TestStatus:
    def test_status_no_server_returns_running_false(self, lifecycle_mocks: dict) -> None:
        lifecycle_mocks["server_service"].find_server.return_value = None

        result = runner.invoke(app, ["status", "--json"])

        assert result.exit_code == ExitCode.SUCCESS
        payload = _json_from(result.stdout)
        assert payload["running"] is False
        assert "project" in payload

    def test_status_running_merges_state_with_admin_info(self, lifecycle_mocks: dict) -> None:
        existing = _state(lifecycle_mocks["project_path"], ProjectStatus.READY)
        lifecycle_mocks["server_service"].find_server.return_value = existing
        lifecycle_mocks["client"].info.return_value = {
            "uptime": "5s",
            "idleDuration": "0s",
            "apiVersion": "v1",
        }

        result = runner.invoke(app, ["status", "--json"])

        assert result.exit_code == ExitCode.SUCCESS
        payload = _json_from(result.stdout)
        assert payload["uptime"] == "5s"
        assert payload["idleDuration"] == "0s"
        assert payload["apiVersion"] == "v1"
        assert payload["port"] == 8080  # from persisted state

    def test_status_falls_back_to_state_only_when_admin_info_unreachable(self, lifecycle_mocks: dict) -> None:
        existing = _state(lifecycle_mocks["project_path"], ProjectStatus.READY)
        lifecycle_mocks["server_service"].find_server.return_value = existing
        lifecycle_mocks["client"].info.side_effect = ConnectionError("refused")

        result = runner.invoke(app, ["status", "--json"])

        assert result.exit_code == ExitCode.SUCCESS
        payload = _json_from(result.stdout)
        assert payload["port"] == 8080
        # No admin-info fields should be present.
        assert "uptime" not in payload or payload.get("uptime") is None


# ============================== restart ==============================


class TestRestart:
    def test_restart_always_calls_stop_first(self, lifecycle_mocks: dict) -> None:
        started = _state(lifecycle_mocks["project_path"], ProjectStatus.READY)

        async def fake_start_server(*args, **kwargs):
            return started

        lifecycle_mocks["server_service"].start_server.side_effect = fake_start_server
        lifecycle_mocks["server_service"].stop_server.return_value = False  # nothing was running

        result = runner.invoke(app, ["restart", "--json"])

        assert result.exit_code == ExitCode.SUCCESS
        lifecycle_mocks["server_service"].stop_server.assert_called_once()
        lifecycle_mocks["server_service"].start_server.assert_called_once()


# ============================== refresh ==============================


class TestRefresh:
    def test_refresh_with_no_server_returns_exit_7(self, lifecycle_mocks: dict) -> None:
        lifecycle_mocks["server_service"].find_server.return_value = None

        result = runner.invoke(app, ["refresh", "--json"])

        assert result.exit_code == ExitCode.NOT_RUNNING
        assert result.exit_code == 7

    def test_refresh_success_returns_project_info(self, lifecycle_mocks: dict) -> None:
        existing = _state(lifecycle_mocks["project_path"], ProjectStatus.READY)
        lifecycle_mocks["server_service"].find_server.return_value = existing
        lifecycle_mocks["project_service"].refresh_project.return_value = ProjectInfo(
            name=lifecycle_mocks["project_path"].name,
            path=str(lifecycle_mocks["project_path"]),
            status=ProjectStatus.READY,
            classCount=42,
            handlerCount=3,
            scannedAt="2026-05-21T00:00:00Z",
        )

        result = runner.invoke(app, ["refresh", "--json"])

        assert result.exit_code == ExitCode.SUCCESS
        payload = _json_from(result.stdout)
        assert payload["classCount"] == 42
        assert payload["status"] == "READY"


# ============================== list ==============================


class TestList:
    def test_list_empty_returns_empty_servers_list(self, lifecycle_mocks: dict) -> None:
        lifecycle_mocks["list_server_service"].list_servers.return_value = []

        result = runner.invoke(app, ["list", "--json"])

        assert result.exit_code == ExitCode.SUCCESS
        payload = _json_from(result.stdout)
        assert payload == {"servers": []}

    def test_list_returns_server_dump_under_servers_key(self, lifecycle_mocks: dict, temp_project_dir: Path) -> None:
        s1 = _state(temp_project_dir, ProjectStatus.READY)
        lifecycle_mocks["list_server_service"].list_servers.return_value = [s1]

        result = runner.invoke(app, ["list", "--json"])

        assert result.exit_code == ExitCode.SUCCESS
        payload = _json_from(result.stdout)
        assert isinstance(payload["servers"], list)
        assert len(payload["servers"]) == 1
        first = payload["servers"][0]
        # camelCase wire format, like the state file.
        assert "projectPath" in first
        assert "serverMode" in first
        assert first["port"] == 8080


# ============================== TTY contract ==============================


class TestTtyBehavior:
    def test_non_tty_emits_json_without_explicit_flag(self, lifecycle_mocks: dict) -> None:
        """CliRunner runs without a TTY; commands should auto-JSON.

        Locks the documented behavior at lifecycle.py: ``if json_output or not is_tty(): print_json(...)``
        """
        lifecycle_mocks["server_service"].find_server.return_value = None

        result = runner.invoke(app, ["status"])  # no --json

        assert result.exit_code == ExitCode.SUCCESS
        payload = _json_from(result.stdout)
        assert payload["running"] is False
