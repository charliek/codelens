"""Tests for CodeLens CLI models."""

from pathlib import Path

from codelens_cli.models import ProjectStatus, ServerMode, ServerState


class TestProjectStatus:
    """Tests for ProjectStatus enum."""

    def test_status_values(self) -> None:
        """Verify all expected status values exist."""
        assert ProjectStatus.LOADING.value == "LOADING"
        assert ProjectStatus.READY.value == "READY"
        assert ProjectStatus.ERROR.value == "ERROR"


class TestServerMode:
    """Tests for ServerMode enum."""

    def test_mode_values(self) -> None:
        """Verify all expected mode values exist."""
        assert ServerMode.AUTO.value == "auto"
        assert ServerMode.GRADLE.value == "gradle"
        assert ServerMode.JAR.value == "jar"


class TestServerState:
    """Tests for ServerState model."""

    def test_server_state_creation(self) -> None:
        """Verify ServerState can be created with required fields."""
        from datetime import datetime, timezone

        now = datetime.now(timezone.utc)
        state = ServerState(
            pid=12345,
            port=8080,
            host="127.0.0.1",
            project_path="/test/project",
            project_name="test-project",
            idle_timeout="30m",
            status=ProjectStatus.READY,
            server_mode=ServerMode.JAR,
            started_at=now,
            last_activity_at=now,
            version="0.1.0",
        )
        assert state.pid == 12345
        assert state.port == 8080
        assert state.status == ProjectStatus.READY
        assert state.version == "0.1.0"

    def test_server_state_path_is_path_object(self) -> None:
        """Verify project_path is stored as Path object."""
        from datetime import datetime, timezone

        now = datetime.now(timezone.utc)
        state = ServerState(
            pid=12345,
            port=8080,
            host="127.0.0.1",
            project_path="/test/project",
            project_name="test-project",
            idle_timeout="30m",
            status=ProjectStatus.READY,
            server_mode=ServerMode.JAR,
            started_at=now,
            last_activity_at=now,
            version="0.1.0",
        )
        assert isinstance(state.project_path, Path)
        assert state.project_path == Path("/test/project")

    def test_server_state_model_dump_serializes_path_to_string(self) -> None:
        """Verify model_dump with mode='json' serializes Path to string.

        This is important for Rich console output which cannot render Path objects.
        """
        from datetime import datetime, timezone

        now = datetime.now(timezone.utc)
        state = ServerState(
            pid=12345,
            port=8080,
            host="127.0.0.1",
            project_path="/test/project",
            project_name="test-project",
            idle_timeout="30m",
            status=ProjectStatus.READY,
            server_mode=ServerMode.JAR,
            started_at=now,
            last_activity_at=now,
            version="0.1.0",
        )

        # Without mode="json", project_path is a Path object
        dump_default = state.model_dump(by_alias=True)
        assert isinstance(dump_default["projectPath"], Path)

        # With mode="json", project_path is serialized to string
        dump_json = state.model_dump(by_alias=True, mode="json")
        assert isinstance(dump_json["projectPath"], str)
        assert dump_json["projectPath"] == "/test/project"
