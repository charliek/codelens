"""Tests for CodeLens CLI models."""

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
