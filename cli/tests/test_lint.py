"""Tests for CodeLens CLI lint commands."""

from datetime import datetime
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest
from typer.testing import CliRunner

from codelens_cli.main import app
from codelens_cli.models import ProjectStatus, ServerMode, ServerState


runner = CliRunner()


@pytest.fixture
def mock_server_state() -> ServerState:
    """Create a mock server state."""
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


class TestLintCheck:
    """Tests for codelens lint check command."""

    def test_lint_check_file_success_no_errors(
        self, temp_project_dir: Path, mock_server_state: ServerState
    ) -> None:
        """Test linting a single file with no errors."""
        with (
            patch(
                "codelens_cli.commands.lint.ServiceContainer"
            ) as mock_container,
            patch("codelens_cli.commands.lint.CodeLensClient") as mock_client_class,
        ):
            # Setup mocks
            mock_server_service = MagicMock()
            mock_project_service = MagicMock()
            mock_container.server_service.return_value = mock_server_service
            mock_container.project_service.return_value = mock_project_service
            mock_project_service.get_project_path.return_value = temp_project_dir
            mock_server_service.find_server.return_value = mock_server_state

            mock_client = MagicMock()
            mock_client_class.return_value = mock_client
            mock_client.lint_file.return_value = {
                "filePath": str(temp_project_dir / "test.kt"),
                "errors": [],
                "errorCount": 0,
                "durationMs": 50,
            }

            # Create test file
            test_file = temp_project_dir / "test.kt"
            test_file.write_text("fun main() {}")

            result = runner.invoke(app, ["lint", "check", str(test_file)])

            assert result.exit_code == 0
            mock_client.lint_file.assert_called_once()

    def test_lint_check_file_with_errors_exits_nonzero(
        self, temp_project_dir: Path, mock_server_state: ServerState
    ) -> None:
        """Test linting a file with errors returns exit code 1."""
        with (
            patch(
                "codelens_cli.commands.lint.ServiceContainer"
            ) as mock_container,
            patch("codelens_cli.commands.lint.CodeLensClient") as mock_client_class,
        ):
            # Setup mocks
            mock_server_service = MagicMock()
            mock_project_service = MagicMock()
            mock_container.server_service.return_value = mock_server_service
            mock_container.project_service.return_value = mock_project_service
            mock_project_service.get_project_path.return_value = temp_project_dir
            mock_server_service.find_server.return_value = mock_server_state

            mock_client = MagicMock()
            mock_client_class.return_value = mock_client
            mock_client.lint_file.return_value = {
                "filePath": str(temp_project_dir / "test.kt"),
                "errors": [
                    {
                        "line": 1,
                        "col": 5,
                        "ruleId": "standard:spacing",
                        "detail": "Missing space after fun",
                        "canBeAutoCorrected": True,
                    }
                ],
                "errorCount": 1,
                "durationMs": 50,
            }

            # Create test file
            test_file = temp_project_dir / "test.kt"
            test_file.write_text("fun main() {}")

            result = runner.invoke(app, ["lint", "check", str(test_file)])

            assert result.exit_code == 1

    def test_lint_check_project_success(
        self, temp_project_dir: Path, mock_server_state: ServerState
    ) -> None:
        """Test linting entire project with no errors."""
        with (
            patch(
                "codelens_cli.commands.lint.ServiceContainer"
            ) as mock_container,
            patch("codelens_cli.commands.lint.CodeLensClient") as mock_client_class,
        ):
            # Setup mocks
            mock_server_service = MagicMock()
            mock_project_service = MagicMock()
            mock_container.server_service.return_value = mock_server_service
            mock_container.project_service.return_value = mock_project_service
            mock_project_service.get_project_path.return_value = temp_project_dir
            mock_server_service.find_server.return_value = mock_server_state

            mock_client = MagicMock()
            mock_client_class.return_value = mock_client
            mock_client.lint_project.return_value = {
                "projectPath": str(temp_project_dir),
                "fileResults": [],
                "filesScanned": 10,
                "filesWithErrors": 0,
                "totalErrorCount": 0,
                "durationMs": 500,
            }

            result = runner.invoke(
                app, ["lint", "check", "-p", str(temp_project_dir)]
            )

            assert result.exit_code == 0
            mock_client.lint_project.assert_called_once()

    def test_lint_check_project_with_errors_exits_nonzero(
        self, temp_project_dir: Path, mock_server_state: ServerState
    ) -> None:
        """Test linting project with errors returns exit code 1."""
        with (
            patch(
                "codelens_cli.commands.lint.ServiceContainer"
            ) as mock_container,
            patch("codelens_cli.commands.lint.CodeLensClient") as mock_client_class,
        ):
            # Setup mocks
            mock_server_service = MagicMock()
            mock_project_service = MagicMock()
            mock_container.server_service.return_value = mock_server_service
            mock_container.project_service.return_value = mock_project_service
            mock_project_service.get_project_path.return_value = temp_project_dir
            mock_server_service.find_server.return_value = mock_server_state

            mock_client = MagicMock()
            mock_client_class.return_value = mock_client
            mock_client.lint_project.return_value = {
                "projectPath": str(temp_project_dir),
                "fileResults": [
                    {
                        "filePath": str(temp_project_dir / "Bad.kt"),
                        "errors": [
                            {
                                "line": 1,
                                "col": 5,
                                "ruleId": "standard:spacing",
                                "detail": "Missing space",
                                "canBeAutoCorrected": True,
                            }
                        ],
                        "errorCount": 1,
                    }
                ],
                "filesScanned": 10,
                "filesWithErrors": 1,
                "totalErrorCount": 1,
                "durationMs": 500,
            }

            result = runner.invoke(
                app, ["lint", "check", "-p", str(temp_project_dir)]
            )

            assert result.exit_code == 1

    def test_lint_check_json_output(
        self, temp_project_dir: Path, mock_server_state: ServerState
    ) -> None:
        """Test lint check with --json flag outputs JSON."""
        with (
            patch(
                "codelens_cli.commands.lint.ServiceContainer"
            ) as mock_container,
            patch("codelens_cli.commands.lint.CodeLensClient") as mock_client_class,
        ):
            # Setup mocks
            mock_server_service = MagicMock()
            mock_project_service = MagicMock()
            mock_container.server_service.return_value = mock_server_service
            mock_container.project_service.return_value = mock_project_service
            mock_project_service.get_project_path.return_value = temp_project_dir
            mock_server_service.find_server.return_value = mock_server_state

            mock_client = MagicMock()
            mock_client_class.return_value = mock_client
            mock_client.lint_project.return_value = {
                "projectPath": str(temp_project_dir),
                "fileResults": [],
                "filesScanned": 5,
                "filesWithErrors": 0,
                "totalErrorCount": 0,
                "durationMs": 100,
            }

            result = runner.invoke(
                app, ["lint", "check", "-p", str(temp_project_dir), "--json"]
            )

            assert result.exit_code == 0
            assert "filesScanned" in result.output


class TestLintFormat:
    """Tests for codelens lint format command."""

    def test_lint_format_file_success(
        self, temp_project_dir: Path, mock_server_state: ServerState
    ) -> None:
        """Test formatting a single file."""
        with (
            patch(
                "codelens_cli.commands.lint.ServiceContainer"
            ) as mock_container,
            patch("codelens_cli.commands.lint.CodeLensClient") as mock_client_class,
        ):
            # Setup mocks
            mock_server_service = MagicMock()
            mock_project_service = MagicMock()
            mock_container.server_service.return_value = mock_server_service
            mock_container.project_service.return_value = mock_project_service
            mock_project_service.get_project_path.return_value = temp_project_dir
            mock_server_service.find_server.return_value = mock_server_state

            mock_client = MagicMock()
            mock_client_class.return_value = mock_client
            mock_client.format_file.return_value = {
                "filePath": str(temp_project_dir / "test.kt"),
                "hasChanges": True,
                "formattedContent": None,
                "remainingErrors": [],
                "durationMs": 50,
            }

            # Create test file
            test_file = temp_project_dir / "test.kt"
            test_file.write_text("fun main() {}")

            result = runner.invoke(app, ["lint", "format", str(test_file)])

            assert result.exit_code == 0
            mock_client.format_file.assert_called_once()
            # Verify writeToFile=True (not dry run)
            call_args = mock_client.format_file.call_args
            assert call_args[1]["write_to_file"] is True

    def test_lint_format_dry_run_does_not_modify(
        self, temp_project_dir: Path, mock_server_state: ServerState
    ) -> None:
        """Test --dry-run flag doesn't modify files."""
        with (
            patch(
                "codelens_cli.commands.lint.ServiceContainer"
            ) as mock_container,
            patch("codelens_cli.commands.lint.CodeLensClient") as mock_client_class,
        ):
            # Setup mocks
            mock_server_service = MagicMock()
            mock_project_service = MagicMock()
            mock_container.server_service.return_value = mock_server_service
            mock_container.project_service.return_value = mock_project_service
            mock_project_service.get_project_path.return_value = temp_project_dir
            mock_server_service.find_server.return_value = mock_server_state

            mock_client = MagicMock()
            mock_client_class.return_value = mock_client
            mock_client.format_file.return_value = {
                "filePath": str(temp_project_dir / "test.kt"),
                "hasChanges": True,
                "formattedContent": "formatted code",
                "remainingErrors": [],
                "durationMs": 50,
            }

            # Create test file
            test_file = temp_project_dir / "test.kt"
            test_file.write_text("fun main() {}")

            result = runner.invoke(
                app, ["lint", "format", str(test_file), "--dry-run"]
            )

            assert result.exit_code == 0
            # Verify writeToFile=False (dry run)
            call_args = mock_client.format_file.call_args
            assert call_args[1]["write_to_file"] is False

    def test_lint_format_project_success(
        self, temp_project_dir: Path, mock_server_state: ServerState
    ) -> None:
        """Test formatting entire project."""
        with (
            patch(
                "codelens_cli.commands.lint.ServiceContainer"
            ) as mock_container,
            patch("codelens_cli.commands.lint.CodeLensClient") as mock_client_class,
        ):
            # Setup mocks
            mock_server_service = MagicMock()
            mock_project_service = MagicMock()
            mock_container.server_service.return_value = mock_server_service
            mock_container.project_service.return_value = mock_project_service
            mock_project_service.get_project_path.return_value = temp_project_dir
            mock_server_service.find_server.return_value = mock_server_state

            mock_client = MagicMock()
            mock_client_class.return_value = mock_client
            mock_client.format_project.return_value = {
                "projectPath": str(temp_project_dir),
                "filesFormatted": ["file1.kt", "file2.kt"],
                "filesScanned": 10,
                "filesWithChanges": 2,
                "durationMs": 500,
            }

            result = runner.invoke(
                app, ["lint", "format", "-p", str(temp_project_dir)]
            )

            assert result.exit_code == 0
            mock_client.format_project.assert_called_once()

    def test_lint_format_no_changes_needed(
        self, temp_project_dir: Path, mock_server_state: ServerState
    ) -> None:
        """Test formatting when no changes are needed."""
        with (
            patch(
                "codelens_cli.commands.lint.ServiceContainer"
            ) as mock_container,
            patch("codelens_cli.commands.lint.CodeLensClient") as mock_client_class,
            patch("codelens_cli.commands.lint.is_tty", return_value=True),
        ):
            # Setup mocks
            mock_server_service = MagicMock()
            mock_project_service = MagicMock()
            mock_container.server_service.return_value = mock_server_service
            mock_container.project_service.return_value = mock_project_service
            mock_project_service.get_project_path.return_value = temp_project_dir
            mock_server_service.find_server.return_value = mock_server_state

            mock_client = MagicMock()
            mock_client_class.return_value = mock_client
            mock_client.format_file.return_value = {
                "filePath": str(temp_project_dir / "test.kt"),
                "hasChanges": False,
                "formattedContent": None,
                "remainingErrors": [],
                "durationMs": 50,
            }

            # Create test file
            test_file = temp_project_dir / "test.kt"
            test_file.write_text("fun main() {}")

            result = runner.invoke(app, ["lint", "format", str(test_file)])

            assert result.exit_code == 0
            assert "No changes needed" in result.output

    def test_lint_format_with_remaining_errors(
        self, temp_project_dir: Path, mock_server_state: ServerState
    ) -> None:
        """Test formatting reports remaining non-auto-fixable errors."""
        with (
            patch(
                "codelens_cli.commands.lint.ServiceContainer"
            ) as mock_container,
            patch("codelens_cli.commands.lint.CodeLensClient") as mock_client_class,
            patch("codelens_cli.commands.lint.is_tty", return_value=True),
        ):
            # Setup mocks
            mock_server_service = MagicMock()
            mock_project_service = MagicMock()
            mock_container.server_service.return_value = mock_server_service
            mock_container.project_service.return_value = mock_project_service
            mock_project_service.get_project_path.return_value = temp_project_dir
            mock_server_service.find_server.return_value = mock_server_state

            mock_client = MagicMock()
            mock_client_class.return_value = mock_client
            mock_client.format_file.return_value = {
                "filePath": str(temp_project_dir / "test.kt"),
                "hasChanges": True,
                "formattedContent": None,
                "remainingErrors": [
                    {
                        "line": 5,
                        "col": 10,
                        "ruleId": "standard:max-line-length",
                        "detail": "Line too long",
                        "canBeAutoCorrected": False,
                    }
                ],
                "durationMs": 50,
            }

            # Create test file
            test_file = temp_project_dir / "test.kt"
            test_file.write_text("fun main() {}")

            result = runner.invoke(app, ["lint", "format", str(test_file)])

            assert result.exit_code == 0
            assert "could not be auto-fixed" in result.output
