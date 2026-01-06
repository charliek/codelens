"""Tests for CodeLens CLI lint commands."""

from pathlib import Path
from unittest.mock import MagicMock

from typer.testing import CliRunner

from codelens_cli.main import app
from codelens_cli.models import (
    FileLintResult,
    FormatFileResponse,
    FormatProjectResponse,
    LintError,
    LintFileResponse,
    LintProjectResponse,
)


runner = CliRunner()


class TestLintCheck:
    """Tests for codelens lint check command."""

    def test_lint_check_file_success_no_errors(
        self,
        temp_project_dir: Path,
        mock_services: tuple[MagicMock, MagicMock, MagicMock],
        mock_client: MagicMock,
    ) -> None:
        """Test linting a single file with no errors."""
        mock_client.lint_file.return_value = LintFileResponse(
            filePath=str(temp_project_dir / "test.kt"),
            errors=[],
            errorCount=0,
            durationMs=50,
        )

        # Create test file
        test_file = temp_project_dir / "test.kt"
        test_file.write_text("fun main() {}")

        result = runner.invoke(app, ["lint", "check", str(test_file)])

        assert result.exit_code == 0
        mock_client.lint_file.assert_called_once()

    def test_lint_check_file_with_errors_exits_nonzero(
        self,
        temp_project_dir: Path,
        mock_services: tuple[MagicMock, MagicMock, MagicMock],
        mock_client: MagicMock,
    ) -> None:
        """Test linting a file with errors returns exit code 1."""
        mock_client.lint_file.return_value = LintFileResponse(
            filePath=str(temp_project_dir / "test.kt"),
            errors=[
                LintError(
                    line=1,
                    col=5,
                    ruleId="standard:spacing",
                    detail="Missing space after fun",
                    canBeAutoCorrected=True,
                )
            ],
            errorCount=1,
            durationMs=50,
        )

        # Create test file
        test_file = temp_project_dir / "test.kt"
        test_file.write_text("fun main() {}")

        result = runner.invoke(app, ["lint", "check", str(test_file)])

        assert result.exit_code == 1

    def test_lint_check_project_success(
        self,
        temp_project_dir: Path,
        mock_services: tuple[MagicMock, MagicMock, MagicMock],
        mock_client: MagicMock,
    ) -> None:
        """Test linting entire project with no errors."""
        mock_client.lint_project.return_value = LintProjectResponse(
            projectPath=str(temp_project_dir),
            fileResults=[],
            filesScanned=10,
            filesWithErrors=0,
            totalErrorCount=0,
            durationMs=500,
        )

        result = runner.invoke(
            app, ["lint", "check", "-p", str(temp_project_dir)]
        )

        assert result.exit_code == 0
        mock_client.lint_project.assert_called_once()

    def test_lint_check_project_with_errors_exits_nonzero(
        self,
        temp_project_dir: Path,
        mock_services: tuple[MagicMock, MagicMock, MagicMock],
        mock_client: MagicMock,
    ) -> None:
        """Test linting project with errors returns exit code 1."""
        mock_client.lint_project.return_value = LintProjectResponse(
            projectPath=str(temp_project_dir),
            fileResults=[
                FileLintResult(
                    filePath=str(temp_project_dir / "Bad.kt"),
                    errors=[
                        LintError(
                            line=1,
                            col=5,
                            ruleId="standard:spacing",
                            detail="Missing space",
                            canBeAutoCorrected=True,
                        )
                    ],
                    errorCount=1,
                )
            ],
            filesScanned=10,
            filesWithErrors=1,
            totalErrorCount=1,
            durationMs=500,
        )

        result = runner.invoke(
            app, ["lint", "check", "-p", str(temp_project_dir)]
        )

        assert result.exit_code == 1

    def test_lint_check_json_output(
        self,
        temp_project_dir: Path,
        mock_services: tuple[MagicMock, MagicMock, MagicMock],
        mock_client: MagicMock,
    ) -> None:
        """Test lint check with --json flag outputs JSON."""
        mock_client.lint_project.return_value = LintProjectResponse(
            projectPath=str(temp_project_dir),
            fileResults=[],
            filesScanned=5,
            filesWithErrors=0,
            totalErrorCount=0,
            durationMs=100,
        )

        result = runner.invoke(
            app, ["lint", "check", "-p", str(temp_project_dir), "--json"]
        )

        assert result.exit_code == 0
        assert "filesScanned" in result.output


class TestLintFormat:
    """Tests for codelens lint format command."""

    def test_lint_format_file_success(
        self,
        temp_project_dir: Path,
        mock_services: tuple[MagicMock, MagicMock, MagicMock],
        mock_client: MagicMock,
    ) -> None:
        """Test formatting a single file."""
        mock_client.format_file.return_value = FormatFileResponse(
            filePath=str(temp_project_dir / "test.kt"),
            hasChanges=True,
            formattedContent=None,
            remainingErrors=[],
            durationMs=50,
        )

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
        self,
        temp_project_dir: Path,
        mock_services: tuple[MagicMock, MagicMock, MagicMock],
        mock_client: MagicMock,
    ) -> None:
        """Test --dry-run flag doesn't modify files."""
        mock_client.format_file.return_value = FormatFileResponse(
            filePath=str(temp_project_dir / "test.kt"),
            hasChanges=True,
            formattedContent="formatted code",
            remainingErrors=[],
            durationMs=50,
        )

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
        self,
        temp_project_dir: Path,
        mock_services: tuple[MagicMock, MagicMock, MagicMock],
        mock_client: MagicMock,
    ) -> None:
        """Test formatting entire project."""
        mock_client.format_project.return_value = FormatProjectResponse(
            projectPath=str(temp_project_dir),
            filesFormatted=["file1.kt", "file2.kt"],
            filesScanned=10,
            filesWithChanges=2,
            durationMs=500,
        )

        result = runner.invoke(
            app, ["lint", "format", "-p", str(temp_project_dir)]
        )

        assert result.exit_code == 0
        mock_client.format_project.assert_called_once()

    def test_lint_format_no_changes_needed(
        self,
        temp_project_dir: Path,
        mock_services: tuple[MagicMock, MagicMock, MagicMock],
        mock_client: MagicMock,
    ) -> None:
        """Test formatting when no changes are needed."""
        from unittest.mock import patch

        with (
            patch("codelens_cli.commands.common.is_tty", return_value=True),
            patch("codelens_cli.commands.lint.is_tty", return_value=True),
        ):
            mock_client.format_file.return_value = FormatFileResponse(
                filePath=str(temp_project_dir / "test.kt"),
                hasChanges=False,
                formattedContent=None,
                remainingErrors=[],
                durationMs=50,
            )

            # Create test file
            test_file = temp_project_dir / "test.kt"
            test_file.write_text("fun main() {}")

            result = runner.invoke(app, ["lint", "format", str(test_file)])

            assert result.exit_code == 0
            assert "No changes needed" in result.output

    def test_lint_format_with_remaining_errors(
        self,
        temp_project_dir: Path,
        mock_services: tuple[MagicMock, MagicMock, MagicMock],
        mock_client: MagicMock,
    ) -> None:
        """Test formatting reports remaining non-auto-fixable errors."""
        from unittest.mock import patch

        with (
            patch("codelens_cli.commands.common.is_tty", return_value=True),
            patch("codelens_cli.commands.lint.is_tty", return_value=True),
        ):
            mock_client.format_file.return_value = FormatFileResponse(
                filePath=str(temp_project_dir / "test.kt"),
                hasChanges=True,
                formattedContent=None,
                remainingErrors=[
                    LintError(
                        line=5,
                        col=10,
                        ruleId="standard:max-line-length",
                        detail="Line too long",
                        canBeAutoCorrected=False,
                    )
                ],
                durationMs=50,
            )

            # Create test file
            test_file = temp_project_dir / "test.kt"
            test_file.write_text("fun main() {}")

            result = runner.invoke(app, ["lint", "format", str(test_file)])

            assert result.exit_code == 0
            assert "could not be auto-fixed" in result.output
